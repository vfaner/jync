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
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlBodyConverter;

/**
 * A saved view override is executed verbatim, and reviewers paste (or accept from a model) text
 * that ends with the statement terminator: {@code CREATE VIEW v AS SELECT 1;}.
 *
 * <p>Oracle — and the Oracle-compatible products the app supports — reject a trailing semicolon
 * on a plain DDL statement submitted through JDBC with ORA-00911. The generated view SQL is
 * already stripped of it (see {@code OracleDialect}); an override bypasses that path, so a
 * perfectly valid override failed on every sync cycle without ever converging.
 */
class StructureSyncServiceOverrideTerminatorTest {

    private static final String H2_URL = "jdbc:h2:mem:override_term;DB_CLOSE_DELAY=-1";

    private Connection rawTarget;
    private List<String> executed;
    private StructureSyncService structure;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        rawTarget = DriverManager.getConnection(H2_URL, "sa", "");
        executed = new ArrayList<>();

        structure = new StructureSyncService(new SqlBodyConverter());

        Project project = new Project();
        project.setId(7L);
        project.setName("P");

        SyncConfig config = new SyncConfig();
        config.getDdlOverrides().put("VIEW:V_TAIL", "CREATE VIEW V_TAIL AS SELECT 1;");

        ctx = SyncContext.builder()
                .project(project)
                .config(config)
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = rawTarget.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        rawTarget.close();
    }

    @Test
    void aTrailingSemicolonInAViewOverrideIsNotSentToTheDriver() throws SQLException {
        ViewMeta view = new ViewMeta();
        view.setName("V_TAIL");
        view.setDefinition("SELECT 1");
        ChangeEvent create = ChangeEvent.of(ObjectType.VIEW, ChangeType.CREATE,
                "V_TAIL", "view create", view);

        StructureSyncService.ApplyOutcome outcome = structure.apply(capturing(rawTarget), create, ctx);

        assertThat(outcome.applied).isTrue();
        // H2 tolerates the terminator; Oracle does not. What the driver receives is the contract.
        String createSql = executed.stream()
                .filter(sql -> sql.startsWith("CREATE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the override never reached the driver"));
        assertThat(createSql).doesNotEndWith(";");
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

    /** Reflection invoke that surfaces the real target exception instead of wrapping it. */
    private static Object invoke(java.lang.reflect.Method method, Object delegate,
                                 Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
