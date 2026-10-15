package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.metadata.OracleMetadataReader;
import com.qqmu.jync.service.sync.DataSyncService;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.util.TargetTableVerifier;

/**
 * End-to-end proof on real Oracle 23ai Free for the two v1.7.2 target-side fixes:
 * <ol>
 *   <li>lowercase source identifiers fold to the conventional Oracle object — one
 *       {@code ORDERS} table, never a quoted-lowercase twin;</li>
 *   <li>autoincrement contracts work on the target: 12c identity columns are created,
 *       the post-load generator reseed really restarts past MAX(id), the pre-12c
 *       sequence+trigger emulation is valid SQL that the metadata reader reconstructs,
 *       and a pre-existing GENERATED ALWAYS table is refused up front.</li>
 * </ol>
 *
 * <p>Named {@code *IT} so surefire's default includes skip it; run explicitly with
 * {@code mvn test -Dtest=OracleTargetIdentityIT}. Without Docker the class disables itself.
 */
@Testcontainers(disabledWithoutDocker = true)
class OracleTargetIdentityIT {

    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    /**
     * Real 11.2 XE. The testcontainers {@code OracleContainer} is 18c+/PDB-oriented (it pins
     * the freepdb1 service), so 11g's non-CDB {@code XE} service is driven directly with the
     * same env vars the manual image documents.
     */
    private static final GenericContainer<?> ORACLE_11 =
            new GenericContainer<>(DockerImageName.parse("gvenzl/oracle-xe:11-slim"))
                    .withExposedPorts(1521)
                    .withEnv("ORACLE_PASSWORD", "jync123")
                    .waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE!.*\\n", 1))
                    .withStartupTimeout(Duration.ofSeconds(900));

    private static Connection conn;
    private static OracleDialect modern;
    private static OracleDialect legacy;

    @BeforeAll
    static void start() throws SQLException {
        ORACLE.start();
        conn = DriverManager.getConnection(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword());
        modern = new OracleDialect(23);
        legacy = new OracleDialect(11);
    }

    @AfterAll
    static void stop() throws SQLException {
        if (conn != null) {
            conn.close();
        }
    }

    // --- case folding + identity reseed ---------------------------------------------------

    @Test
    void lowercaseSourceCreatesOneUppercaseTableAndReseedAdvancesIdentity() throws SQLException {
        dropQuietly("TABLE", "ORDERS");
        TableMeta table = ordersMeta();

        // As StructureSyncService would emit them for a MySQL-source "orders".
        String create = modern.getCreateTableSql(table, null, "orders", DatabaseType.MYSQL);
        try (Statement st = conn.createStatement()) {
            st.execute(create);
            for (String extra : modern.getPostCreateTableDdl(table, null, "orders",
                    DatabaseType.MYSQL)) {
                st.execute(extra);
            }
        }

        assertThat(tableCount("ORDERS")).as("folded conventional table exists").isEqualTo(1);
        assertThat(tableCount("orders")).as("no quoted-lowercase twin").isZero();

        // Bulk load carries explicit ids, exactly as data sync writes them.
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO \"ORDERS\" (\"ID\", \"NAME\") VALUES (?, ?)")) {
            ps.setLong(1, 10L);
            ps.setString(2, "ten");
            ps.executeUpdate();
            ps.setLong(1, 20L);
            ps.setString(2, "twenty");
            ps.executeUpdate();
        }

        SyncContext ctx = SyncContext.builder()
                .targetType(DatabaseType.ORACLE)
                .targetDialect(modern)
                .config(new SyncConfig())
                .build();
        new DataSyncService(new SyncProperties())
                .reseedIdentityAfterInitialLoad(conn, table, ctx);

        // An application insert that omits the id now draws 21 instead of colliding at 1.
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO \"ORDERS\" (\"NAME\") VALUES ('post-reseed')");
        }
        assertThat(generatedIdFor("post-reseed")).isEqualTo(21L);

        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT GENERATION_TYPE FROM ALL_TAB_IDENTITY_COLS"
                        + " WHERE OWNER = USER AND TABLE_NAME = 'ORDERS' AND COLUMN_NAME = 'ID'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("BY DEFAULT");
        }
    }

    // --- pre-existing GENERATED ALWAYS ----------------------------------------------------

    @Test
    void preExistingAlwaysIdentityTableIsRefusedWithAnActionableReason() throws SQLException {
        dropQuietly("TABLE", "ALW");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE \"ALW\" (\"ID\" NUMBER GENERATED ALWAYS AS IDENTITY"
                    + " PRIMARY KEY, \"NAME\" VARCHAR2(50))");
        }
        TableMeta source = ordersMeta("ALW");

        String reason = TargetTableVerifier.verify(conn, null, "alw", source,
                DatabaseType.ORACLE);
        assertThat(reason).contains("ORA-32795").contains("ALW");

        // Control: without the Oracle-family fold the lowercase lookup finds no table at all.
        String raw = TargetTableVerifier.verify(conn, null, "alw", source, DatabaseType.MYSQL);
        assertThat(raw).contains("missing column");
    }

    // --- metadata detection ---------------------------------------------------------------

    @Test
    void readerDetectsNativeIdentityAndSequenceTriggerEmulation() throws SQLException {
        dropQuietly("TABLE", "IDT");
        dropQuietly("TABLE", "LEG");
        dropQuietly("SEQUENCE", "SEQ_LEG_ID");

        // 12c identity created directly (what a hand-made modern target looks like).
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE \"IDT\" (\"ID\" NUMBER GENERATED BY DEFAULT AS IDENTITY"
                    + " PRIMARY KEY, \"N\" VARCHAR2(50))");
        }
        TableMeta idt = new OracleMetadataReader().readTable(conn, null, "IDT");
        ColumnMeta idtId = idt.getColumns().stream()
                .filter(c -> "ID".equalsIgnoreCase(c.getName())).findFirst().orElseThrow();
        assertThat(idtId.isAutoIncrement())
                .as("12c identity column detected (dictionary view and/or JDBC)").isTrue();

        // The 11g emulation the dialect emits pre-12c must be executable on a real server AND
        // reconstructable as autoincrement from ALL_TRIGGERS (the column itself is plain).
        TableMeta leg = ordersMeta("LEG");
        String createLeg = legacy.getCreateTableSql(leg, null, "LEG", DatabaseType.MYSQL);
        try (Statement st = conn.createStatement()) {
            st.execute(createLeg);
            for (String extra : legacy.getPostCreateTableDdl(leg, null, "LEG",
                    DatabaseType.MYSQL)) {
                st.execute(extra);
            }
            st.execute("INSERT INTO \"LEG\" (\"NAME\") VALUES ('trigger-fed')");
        }
        TableMeta legRead = new OracleMetadataReader().readTable(conn, null, "LEG");
        ColumnMeta legId = legRead.getColumns().stream()
                .filter(c -> "ID".equalsIgnoreCase(c.getName())).findFirst().orElseThrow();
        assertThat(legId.isAutoIncrement())
                .as("pre-12c sequence+trigger emulation reconstructed from ALL_TRIGGERS").isTrue();
        assertThat(generatedIdFor("LEG", "trigger-fed")).isEqualTo(1L);
    }

    // --- real 11g XE: detection and portable emulation ------------------------------------

    @Test
    void elevenGTargetIsDetectedAndRunsTheSequenceTriggerEmulationEndToEnd() throws SQLException {
        ORACLE_11.start();
        String url11 = "jdbc:oracle:thin:@//" + ORACLE_11.getHost() + ":"
                + ORACLE_11.getMappedPort(1521) + "/XE";
        try (Connection c11 = DriverManager.getConnection(url11, "SYSTEM", "jync123")) {

            // The gate's input: the driver must report 11 against a real 11.2 server.
            assertThat(c11.getMetaData().getDatabaseMajorVersion()).isEqualTo(11);

            TableMeta table = ordersMeta("LEG11");
            dropTableQuietly(c11, "LEG11");

            // The modern clause is genuinely rejected by 11g (parser error, not our choice).
            String identityCreate = modern.getCreateTableSql(table, null, "LEG11",
                    DatabaseType.MYSQL);
            try (Statement st = c11.createStatement()) {
                st.execute(identityCreate);
                org.junit.jupiter.api.Assertions.fail(
                        "11g must reject AS IDENTITY, but accepted: " + identityCreate);
            } catch (SQLException expected) {
                // 11.2 reports ORA-00907 or ORA-02000 depending on patch level; both parse.
                assertThat(expected.getErrorCode()).isIn(907, 2000);
            }

            // The version-gated path the app actually takes: plain column, then sequence
            // and BEFORE-INSERT trigger.
            String create = legacy.getCreateTableSql(table, null, "LEG11", DatabaseType.MYSQL);
            try (Statement st = c11.createStatement()) {
                st.execute(create);
                for (String extra : legacy.getPostCreateTableDdl(table, null, "LEG11",
                        DatabaseType.MYSQL)) {
                    st.execute(extra);
                }
                // Explicit id (bulk sync) kept; omitted id fed by the trigger.
                st.execute("INSERT INTO \"LEG11\" (\"ID\", \"NAME\") VALUES (77, 'explicit')");
                st.execute("INSERT INTO \"LEG11\" (\"NAME\") VALUES ('trigger-fed')");
                try (ResultSet rs = st.executeQuery(
                        "SELECT \"ID\" FROM \"LEG11\" ORDER BY \"ID\"")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isEqualTo(1L);
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isEqualTo(77L);
                }
            }
        }
    }

    private static void dropTableQuietly(Connection target, String name) {
        try (Statement st = target.createStatement()) {
            st.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"" + name
                    + "\" CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN NULL; END;");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --- helpers --------------------------------------------------------------------------

    private static TableMeta ordersMeta() {
        return ordersMeta("orders");
    }

    private static TableMeta ordersMeta(String name) {
        TableMeta table = new TableMeta();
        table.setName(name);

        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(Types.BIGINT);
        id.setSize(19);
        id.setNullable(false);
        id.setPrimaryKey(true);
        id.setAutoIncrement(true);

        ColumnMeta label = new ColumnMeta();
        label.setName("name");
        label.setJdbcType(Types.VARCHAR);
        label.setSize(100);

        table.getColumns().addAll(List.of(id, label));
        table.getPrimaryKeys().add("id");
        return table;
    }

    private static int tableCount(String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER = USER AND TABLE_NAME = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static Long generatedIdFor(String nameValue) throws SQLException {
        return generatedIdFor("ORDERS", nameValue);
    }

    private static Long generatedIdFor(String table, String nameValue) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT \"ID\" FROM \"" + table + "\" WHERE \"NAME\" = ?")) {
            ps.setString(1, nameValue);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row %s present", nameValue).isTrue();
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        }
    }

    /** Best-effort cleanup so a local container re-run starts clean; swallows "absent". */
    private static void dropQuietly(String kind, String name) {
        String suffix = "TABLE".equals(kind) ? " CASCADE CONSTRAINTS" : "";
        try (Statement st = conn.createStatement()) {
            st.execute("BEGIN EXECUTE IMMEDIATE 'DROP " + kind + " \"" + name + "\"" + suffix
                    + "'; EXCEPTION WHEN OTHERS THEN NULL; END;");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
