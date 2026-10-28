package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.cdc.LogMinerSession;
import com.qqmu.jync.service.cdc.LogMinerSessionFactoryBean;
import com.qqmu.jync.service.cdc.SqlRedoParser;

/**
 * The DM gate for the dialect seam: the <em>production</em> session factory —
 * {@link LogMinerSessionFactoryBean}, which runs the capability probe and then the DM
 * discovery/ADD_LOGFILE/START_LOGMNR sequence — against a real DM8 instance.
 *
 * <p>The unit tests pin the SQL shapes; this pins that the DM JDBC driver and server accept
 * them as written ({@code CALL DBMS_LOGMNR.ADD_LOGFILE(path, 1)}, named {@code startScn} /
 * {@code endScn}, {@code 0 AS SRC_CON_ID}), that a real transaction is returned by the
 * shared cursor, and that DM's literal renderings (ANSI {@code DATE'…'} / {@code TIMESTAMP'…'},
 * {@code 0x…} for binary) are the ones the parser was taught in the spike.
 *
 * <p>Named {@code *IT} so the default surefire run skips it. Without a reachable
 * {@code jync-dm} container, or without {@code RLOG_APPEND_LOGIC=2} / ARCHIVELOG, the class
 * disables itself instead of failing — the prerequisites are DBA-side and the spike doc
 * (docs/dm-logminer-spike.md §1) already records them.
 *
 * <p>Run with: {@code mvn test -Dtest=DmLogMinerPathIT}
 */
class DmLogMinerPathIT {

    private static final String URL = "jdbc:dm://localhost:15236";
    private static final String USER = "SYSDBA";
    private static final String PASSWORD = "SYSDBA001";
    private static final String TABLE = "DM_IT_T";
    /** DM's archiver only picks up the closed file; allow for the switch to complete. */
    private static final long ARCHIVE_WAIT_MS = 120_000L;

    @Test
    void theProductionFactoryMinesACommittedTransactionAndItsLiteralRenderings()
            throws Exception {
        Connection conn;
        try {
            conn = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException unreachable) {
            Assumptions.assumeTrue(false, "jync-dm container not reachable at " + URL
                    + " (" + unreachable.getMessage() + ")");
            return;
        }
        try (conn) {
            assumeDmIsMineable(conn);
            prepareTable(conn);

            long baseline = maxNextChange(conn);
            Assumptions.assumeTrue(baseline > 0,
                    "no archived log yet — nothing to anchor a baseline on");

            writeTransaction(conn);
            switchAndWaitForArchive(conn, baseline);

            try (LogMinerSession session = new LogMinerSessionFactoryBean()
                    .open(conn, baseline, 500)) {
                List<LogMinerSession.Row> rows = drain(session);
                List<String> redo = rows.stream()
                        .filter(row -> TABLE.equalsIgnoreCase(row.tableName))
                        .map(row -> row.operation + " " + row.sqlRedo)
                        .toList();

                assertThat(redo)
                        .as("the whole committed transaction must come back through the seam")
                        .hasSize(4);
                assertThat(redo).filteredOn(sql -> sql.startsWith("INSERT"))
                        .as("both inserts, each as its own redo record")
                        .hasSize(2)
                        .anySatisfy(sql -> assertThat(sql)
                                .contains("'it-insert'")
                                .as("DM renders DATE as an ANSI literal")
                                .contains("DATE'2026-03-04'")
                                .as("DM renders TIMESTAMP, fraction included, as an ANSI literal")
                                .contains("TIMESTAMP'2026-03-04 05:06:07.123456'"));
                assertThat(redo).anySatisfy(sql -> assertThat(sql)
                        .startsWith("UPDATE")
                        .contains("'it-updated'")
                        // RLOG_APPEND_LOGIC=2 gives the full old image, which is what the
                        // parser turns into the WHERE clause's previous-key values.
                        .contains("\"C_STR\" = 'it-insert'"));
                assertThat(redo).anySatisfy(sql -> assertThat(sql).startsWith("DELETE"));

                // And the parser turns them into the values the applier binds. This is the
                // half a mock cannot prove: the four DM literal shapes go through
                // OracleLiteralConverters exactly as the spike recorded them.
                SqlRedoParser parser = new SqlRedoParser();
                CdcTableBinding binding = binding();

                SqlRedoParser.ParsedChange inserted = parser.parse(SqlRedoParser.Op.INSERT,
                        find(redo, "INSERT", "'it-insert'"), binding);
                assertThat(inserted.unsupportedReason).isNull();
                assertThat(inserted.row)
                        .containsEntry("ID", "1")
                        .containsEntry("C_STR", "it-insert")
                        .containsEntry("C_DEC", "12.5")
                        .as("ANSI DATE literal surfaced verbatim")
                        .containsEntry("C_DATE", "2026-03-04")
                        .as("ANSI TIMESTAMP literal surfaced verbatim, fraction intact")
                        .containsEntry("C_TS", "2026-03-04 05:06:07.123456");
                assertThat(inserted.row.get("C_BIN")).isEqualTo(new byte[]{(byte) 0xDE, (byte) 0xAD});

                SqlRedoParser.ParsedChange updated = parser.parse(SqlRedoParser.Op.UPDATE,
                        find(redo, "UPDATE", null), binding);
                assertThat(updated.unsupportedReason).isNull();
                assertThat(updated.row).containsEntry("C_STR", "it-updated");
                assertThat(updated.previousKey)
                        .as("the WHERE old image is the previous key the applier needs")
                        .containsEntry("ID", "1");

                SqlRedoParser.ParsedChange deleted = parser.parse(SqlRedoParser.Op.DELETE,
                        find(redo, "DELETE", null), binding);
                assertThat(deleted.unsupportedReason).isNull();
                assertThat(deleted.row).as("a DELETE carries the old image, not a new row")
                        .containsEntry("ID", "2");

                // Binary columns arrive as a bare 0x… literal (spike §3); an empty value is
                // a lone "0x". Asserting the shape here keeps the parser branch honest
                // against the server rather than against a recorded string.
                assertThat(redo).anySatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT))
                        .contains("0x"));
            }
        }
    }

    private static void assumeDmIsMineable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            conn.setAutoCommit(true);
            String archMode;
            try (ResultSet rs = st.executeQuery("SELECT ARCH_MODE FROM V$DATABASE")) {
                rs.next();
                archMode = rs.getString(1);
            }
            Assumptions.assumeTrue("Y".equalsIgnoreCase(archMode),
                    "DM instance is not in ARCHIVELOG mode; nothing can be mined");

            String appendLogic;
            try (ResultSet rs = st.executeQuery(
                    "SELECT PARA_VALUE FROM V$DM_INI WHERE PARA_NAME = 'RLOG_APPEND_LOGIC'")) {
                rs.next();
                appendLogic = rs.getString(1);
            }
            Assumptions.assumeTrue("2".equals(appendLogic),
                    "RLOG_APPEND_LOGIC must be 2 (old image of every column), was " + appendLogic);
        }
    }

    private static void prepareTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM \"" + TABLE + "\"");
        } catch (SQLException absent) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE \"" + TABLE + "\" (\"ID\" INT PRIMARY KEY,"
                        + " \"C_STR\" VARCHAR(100), \"C_DEC\" DECIMAL(10,2),"
                        + " \"C_DATE\" DATE, \"C_TS\" TIMESTAMP, \"C_BIN\" VARBINARY(50))");
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM \"" + TABLE + "\"");
        }
    }

    private static void writeTransaction(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO \"" + TABLE + "\"(\"ID\",\"C_STR\",\"C_DEC\",\"C_DATE\","
                    + "\"C_TS\",\"C_BIN\") VALUES(1, 'it-insert', 12.5,"
                    + " DATE'2026-03-04', TIMESTAMP'2026-03-04 05:06:07.123456', 0xDEAD)");
            st.execute("INSERT INTO \"" + TABLE + "\"(\"ID\",\"C_STR\",\"C_DEC\",\"C_DATE\","
                    + "\"C_TS\",\"C_BIN\") VALUES(2, 'it-delete', 1.0,"
                    + " DATE'2026-03-04', TIMESTAMP'2026-03-04 05:06:07.123456', 0x00)");
            st.execute("UPDATE \"" + TABLE + "\" SET \"C_STR\" = 'it-updated' WHERE \"ID\" = 1");
            st.execute("DELETE FROM \"" + TABLE + "\" WHERE \"ID\" = 2");
        }
    }

    private static long maxNextChange(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT MAX(NEXT_CHANGE#) FROM V$ARCHIVED_LOG")) {
            rs.next();
            long value = rs.getLong(1);
            return rs.wasNull() ? -1L : value;
        }
    }

    /**
     * DM can only mine archived files, and the archive appears while the file is still
     * being written (spike §2.3.1). Switch, then wait until the newest archived file's
     * {@code NEXT_CHANGE#} has passed the baseline — that is the earliest moment the
     * transaction can be visible at all.
     */
    private static void switchAndWaitForArchive(Connection conn, long baseline)
            throws SQLException, InterruptedException {
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER SYSTEM SWITCH LOGFILE");
        }
        long deadline = System.currentTimeMillis() + ARCHIVE_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (maxNextChange(conn) > baseline) {
                return;
            }
            Thread.sleep(1_000L);
        }
        Assumptions.assumeTrue(false, "DM did not archive past SCN " + baseline + " within "
                + (ARCHIVE_WAIT_MS / 1000) + "s — the archiver needs a scheduled"
                + " ALTER SYSTEM SWITCH LOGFILE job (spike §1.2)");
    }

    /** The statement text of the one redo entry that starts with {@code op}. */
    private static String find(List<String> redo, String op, String mustContain) {
        return redo.stream()
                .filter(sql -> sql.startsWith(op))
                .filter(sql -> mustContain == null || sql.contains(mustContain))
                .map(sql -> sql.substring(op.length() + 1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + op + " redo entry in " + redo));
    }

    private static CdcTableBinding binding() {
        TableMeta meta = new TableMeta();
        meta.setName(TABLE);
        meta.setColumns(List.of(
                column("ID", Types.INTEGER, true),
                column("C_STR", Types.VARCHAR, false),
                column("C_DEC", Types.DECIMAL, false),
                column("C_DATE", Types.DATE, false),
                column("C_TS", Types.TIMESTAMP, false),
                column("C_BIN", Types.VARBINARY, false)));
        meta.setPrimaryKeys(List.of("ID"));
        return CdcTableBinding.of("SYSDBA", TABLE, meta, TABLE, null);
    }

    private static ColumnMeta column(String name, int jdbcType, boolean primaryKey) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setPrimaryKey(primaryKey);
        return column;
    }

    private static List<LogMinerSession.Row> drain(LogMinerSession session) throws SQLException {
        List<LogMinerSession.Row> all = new java.util.ArrayList<>();
        List<LogMinerSession.Row> batch;
        while (!(batch = session.nextBatch()).isEmpty()) {
            all.addAll(batch);
        }
        return all;
    }
}
