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
import com.qqmu.jync.dto.meta.ProcedureMeta.ParamMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.RoutineTypeMapper;
import com.qqmu.jync.service.converter.SqlBodyConverter;
import com.qqmu.jync.service.converter.SqlServerDialect;

/**
 * Routine headers must carry target-dialect parameter and return types instead of embedding
 * the source dictionary's spelling, and a function whose return type cannot be resolved must
 * be refused instead of inventing one.
 */
class StructureSyncServiceRoutineTypeTest {

    private static final String H2_URL = "jdbc:h2:mem:routine_types;DB_CLOSE_DELAY=-1";

    private Connection target;
    private List<String> executed;
    private StructureSyncService structure;

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection(H2_URL, "sa", "");
        executed = new ArrayList<>();
        structure = new StructureSyncService(new SqlBodyConverter(), new RoutineTypeMapper());
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = target.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        target.close();
    }

    @Test
    void anOracleFunctionHeaderCarriesMysqlTypes() throws SQLException {
        ProcedureMeta proc = new ProcedureMeta();
        proc.setRoutineType("FUNCTION");
        proc.setName("F_TAX");
        proc.setReturnType("NUMBER(10,2)");
        proc.setDefinition("RETURN 1");
        ParamMeta param = new ParamMeta();
        param.setName("P_NOTE");
        param.setMode("IN");
        param.setTypeName("VARCHAR2(100)");
        proc.setParameters(List.of(param));

        ChangeEvent create = ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.CREATE,
                "F_TAX", "function create", proc);
        SyncContext ctx = ctx(DatabaseType.ORACLE, DatabaseType.MYSQL, new MySqlDialect());

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(recording(target), create, ctx);

        assertThat(outcome.applied).isTrue();
        String ddl = executed.stream().filter(sql -> sql.startsWith("CREATE")).findFirst()
                .orElseThrow(() -> new AssertionError("the CREATE never reached the driver"));
        assertThat(ddl).contains("VARCHAR(100)").contains("DECIMAL(10,2)");
        assertThat(ddl).doesNotContain("VARCHAR2");
        // DETERMINISTIC must not be claimed: the converted body cannot be proven deterministic.
        assertThat(ddl).doesNotContain("DETERMINISTIC");
    }

    @Test
    void aFunctionWithoutAResolvableReturnTypeIsBlockedBeforeAnyStatement() throws SQLException {
        ProcedureMeta proc = new ProcedureMeta();
        proc.setRoutineType("FUNCTION");
        proc.setName("F_UNKNOWN");
        // Oracle/SQL Server/DB2/HANA readers do not report an explicit return type.
        proc.setReturnType(null);
        proc.setDefinition("BEGIN RETURN 1; END");
        ParamMeta param = new ParamMeta();
        param.setName("P1");
        param.setTypeName("VARCHAR2(10)");
        proc.setParameters(List.of(param));

        ChangeEvent create = ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.CREATE,
                "F_UNKNOWN", "function create", proc);
        SyncContext ctx = ctx(DatabaseType.ORACLE, DatabaseType.MYSQL, new MySqlDialect());

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(recording(target), create, ctx);

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("F_UNKNOWN").containsIgnoringCase("return type");
        // Neither the pre-DROP nor the CREATE may run: the failure is surfaced before both.
        assertThat(executed).isEmpty();
    }

    @Test
    void anInoutParameterToSqlServerCarriesTheOutputMarker() throws SQLException {
        ProcedureMeta proc = new ProcedureMeta();
        proc.setRoutineType("PROCEDURE");
        proc.setName("P_INOUT");
        // A single statement has no cross-family procedural compound block; BEGIN/END bodies are
        // covered by the procedural-body blocker and should no longer reach the driver.
        proc.setDefinition("SELECT 1");
        ParamMeta param = new ParamMeta();
        param.setName("P_TOTAL");
        param.setMode("INOUT");
        param.setTypeName("INT");
        proc.setParameters(List.of(param));

        ChangeEvent create = ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.CREATE,
                "P_INOUT", "procedure create", proc);
        SyncContext ctx = ctx(DatabaseType.MYSQL, DatabaseType.SQLSERVER,
                new SqlServerDialect());

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(recording(target), create, ctx);

        assertThat(outcome.applied).isTrue();
        String ddl = executed.stream().filter(sql -> sql.startsWith("CREATE")).findFirst()
                .orElseThrow(() -> new AssertionError("the CREATE never reached the driver"));
        assertThat(ddl).contains("@P_TOTAL INT OUTPUT");
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

    /**
     * Record-only proxy: the contract under test is what Jync hands the driver, not whether H2
     * understands MySQL routine DDL, so statements are captured and not executed.
     */
    private Connection recording(Connection delegate) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return statement();
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Statement statement() {
        return (Statement) java.lang.reflect.Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName()) && args[0] instanceof String) {
                        executed.add((String) args[0]);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
