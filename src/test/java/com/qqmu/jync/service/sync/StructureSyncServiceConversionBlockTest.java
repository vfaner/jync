package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.converter.SqlBodyConverter;
import com.qqmu.jync.service.converter.SqlServerDialect;

/**
 * Automatic conversion that would create an object compiling to different semantics must be
 * refused before any statement reaches the driver — a wrong answer with no error is worse than
 * a named failure the operator can act on (rewrite the source or save a DDL override).
 */
class StructureSyncServiceConversionBlockTest {

    private static final String H2_URL = "jdbc:h2:mem:conv_block;DB_CLOSE_DELAY=-1";

    private Connection rawTarget;
    private List<String> executed;
    private StructureSyncService structure;

    @BeforeEach
    void setUp() throws SQLException {
        rawTarget = DriverManager.getConnection(H2_URL, "sa", "");
        executed = new ArrayList<>();
        structure = new StructureSyncService(new SqlBodyConverter());
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = rawTarget.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        rawTarget.close();
    }

    @Test
    void aViewUsingPipeConcatenationIsNotCreatedOnAMysqlTarget() throws SQLException {
        SyncContext ctx = ctx(DatabaseType.ORACLE, DatabaseType.MYSQL, new MySqlDialect());
        ViewMeta view = new ViewMeta();
        view.setName("V_NAME");
        view.setDefinition("CREATE OR REPLACE VIEW V_NAME AS SELECT first_name || last_name"
                + " FROM users");
        ChangeEvent create = ChangeEvent.of(ObjectType.VIEW, ChangeType.CREATE,
                "V_NAME", "view create", view);

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(capturing(rawTarget), create, ctx);

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("V_NAME").contains("||").contains("CONCAT");
        // Nothing must have reached the target: neither a DROP nor the broken CREATE.
        assertThat(executed).isEmpty();
    }

    @Test
    void aViewUsingLimitIsNotCreatedOnASqlServerTarget() throws SQLException {
        SyncContext ctx = ctx(DatabaseType.MYSQL, DatabaseType.SQLSERVER, new SqlServerDialect());
        ViewMeta view = new ViewMeta();
        view.setName("V_LIMIT");
        view.setDefinition("CREATE VIEW V_LIMIT AS SELECT id FROM t ORDER BY id LIMIT 10");
        ChangeEvent create = ChangeEvent.of(ObjectType.VIEW, ChangeType.CREATE,
                "V_LIMIT", "view create", view);

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(capturing(rawTarget), create, ctx);

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("V_LIMIT").containsIgnoringCase("LIMIT");
        assertThat(executed).isEmpty();
    }

    @Test
    void aProceduralRoutineAcrossFamiliesIsNotCreated() throws SQLException {
        SyncContext ctx = ctx(DatabaseType.ORACLE, DatabaseType.POSTGRESQL, new PostgresDialect());
        ProcedureMeta proc = new ProcedureMeta();
        proc.setRoutineType("PROCEDURE");
        proc.setName("P_DEMO");
        proc.setDefinition("CREATE OR REPLACE PROCEDURE P_DEMO IS\n"
                + "BEGIN\n"
                + "  FOR r IN (SELECT id FROM t) LOOP\n"
                + "    INSERT INTO log VALUES (r.id);\n"
                + "  END LOOP;\n"
                + "END;");
        ChangeEvent create = ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.CREATE,
                "P_DEMO", "procedure create", proc);

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(capturing(rawTarget), create, ctx);

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("P_DEMO").contains("procedural routine body");
        assertThat(executed).isEmpty();
    }

    private SyncContext ctx(DatabaseType source, DatabaseType target,
                            com.qqmu.jync.service.converter.SqlDialect targetDialect) {
        Project project = new Project();
        project.setId(7L);
        project.setName("P");
        return SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceType(source)
                .targetType(target)
                .targetDialect(targetDialect)
                .batchSize(50)
                .build();
    }

    /** Records every statement handed to the driver, then runs it against H2. */
    private Connection capturing(Connection delegate) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return recording((Statement) invoke(method, delegate, args));
                    }
                    return invoke(method, delegate, args);
                });
    }

    private Statement recording(Statement delegate) {
        return (Statement) java.lang.reflect.Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName()) && args[0] instanceof String) {
                        executed.add((String) args[0]);
                    }
                    return invoke(method, delegate, args);
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object delegate,
                                 Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
