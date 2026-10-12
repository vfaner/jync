package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The v1.6.0 GATE spike (plan Task 1, as re-scoped by the user-approved dual-mode ruling):
 * proves empirically, on the real container, that LogMiner works against the PDB's redo
 * from a CDB$ROOT session — the ONLY shape Oracle allows on a CDB (measured ORA-65040:
 * ADD_LOGFILE/START_LOGMNR are rejected from inside a PDB), and the shape every
 * containerized Oracle since 18c XE forces the IT family into.
 *
 * <p>What it proves in one pass:
 * <ul>
 *   <li>the harness's measured ARCHIVELOG + supplemental-logging sequence really produces
 *       a mineable database;</li>
 *   <li>a C## common user with the harness's measured grant set can run the full
 *       ADD_LOGFILE → START_LOGMNR(DICT_FROM_ONLINE_CATALOG + COMMITTED_DATA_ONLY) →
 *       V$LOGMNR_CONTENTS → END_LOGMNR cycle from root;</li>
 *   <li>the CDB detection + CON_ID discovery query ({@link #Q_DATABASE_CDB_INFO}) works
 *       from the PDB side, and root-mined contents filtered by that CON_ID contain
 *       exactly the PDB's DML — the per-project isolation the dual-mode design promises;</li>
 *   <li>the online catalog resolves PDB table/column names from the root session
 *       (SQL_REDO carries real identifiers, not OBJ# placeholders);</li>
 *   <li>table-level ALL COLUMNS supplemental logging makes an UPDATE's SQL_REDO carry
 *       every column (the full after-image the applier's upsert needs);</li>
 *   <li>multibyte (中文) and single-quote escapes survive into SQL_REDO intact;</li>
 *   <li>and it records the REAL SQL_REDO texts to
 *       {@code src/test/resources/logminer/samples-23ai-free.txt} — the fixture source
 *       for the Task 4 parser tests (format: {@code -- OPERATION | RS_ID | CSN} header
 *       line, then the SQL_REDO verbatim).</li>
 * </ul>
 *
 * <p>The non-CDB branch (project connection mines directly, no CON_ID filter) is the
 * same code path minus the container hop; it is unit-test-only, since no non-CDB
 * container image exists.
 *
 * <p>Named {@code *IT} so surefire's default includes skip it; run explicitly with
 * {@code mvn test -Dtest=LogMinerPathSpikeIT -Dapi.version=1.44}. Without Docker the
 * class disables itself instead of failing.
 */
class LogMinerPathSpikeIT extends OracleCdcHarnessIT {

    @Test
    void minePdbFromRoot() throws Exception {
        try (Connection src = sourceJdbc()) {
            src.setAutoCommit(false);
            dropQuietly(src, "SPIKE_T");
            try (Statement st = src.createStatement()) {
                st.execute("CREATE TABLE SPIKE_T (ID NUMBER(19) PRIMARY KEY,"
                        + " NAME VARCHAR2(100), NOTE VARCHAR2(200), TS TIMESTAMP(6))");
                st.execute("ALTER TABLE SPIKE_T ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
            }
            long startScn = queryCurrentScn(src);

            // One transaction, five changes: INSERT×3 / UPDATE×1 / DELETE×1.
            try (PreparedStatement ins = src.prepareStatement(
                    "INSERT INTO SPIKE_T (ID, NAME, NOTE, TS) VALUES (?, ?, ?, SYSTIMESTAMP)")) {
                ins.setLong(1, 1L);
                ins.setString(2, "Alice");
                ins.setString(3, "hello 世界");
                ins.executeUpdate();
                ins.setLong(1, 2L);
                ins.setString(2, "Bob");
                ins.setString(3, "it's here");
                ins.executeUpdate();
            }
            try (PreparedStatement upd = src.prepareStatement(
                    "UPDATE SPIKE_T SET NOTE = ? WHERE ID = 1")) {
                upd.setString(1, "更新后的备注 changed");
                upd.executeUpdate();
            }
            try (Statement st = src.createStatement()) {
                st.executeUpdate("DELETE FROM SPIKE_T WHERE ID = 2");
            }
            try (PreparedStatement ins = src.prepareStatement(
                    "INSERT INTO SPIKE_T (ID, NAME, NOTE, TS) VALUES (?, ?, ?, SYSTIMESTAMP)")) {
                ins.setLong(1, 3L);
                ins.setString(2, "Carol");
                ins.setString(3, "third row");
                ins.executeUpdate();
            }
            src.commit();

            // Force the DML's redo into an archive so the log list is deterministic.
            execAsSysdba("ALTER SYSTEM SWITCH LOGFILE;");

            // CDB facts, read from the PDB side — the same query the probe/session factory use.
            String dbName;
            String cdb;
            long conId;
            try (Statement st = src.createStatement();
                 ResultSet rs = st.executeQuery(Q_DATABASE_CDB_INFO)) {
                assertThat(rs.next()).as("V$DATABASE row").isTrue();
                dbName = rs.getString("NAME");
                cdb = rs.getString("CDB");
                conId = rs.getLong("CON_ID");
            }
            assertThat(cdb).as("gvenzl/oracle-free:23 must be a CDB — otherwise this spike "
                    + "is mining in the wrong mode").isEqualTo("YES");
            assertThat(dbName).isNotBlank();
            assertThat(conId).as("PDB-side CON_ID").isPositive();

            // The mining session: C## common user, CDB$ROOT, CON_ID-filtered contents.
            try (Connection miner = minerJdbc()) {
                List<String> logs = new ArrayList<>();
                try (PreparedStatement ps = miner.prepareStatement(Q_ARCHIVED_LOGS_SINCE)) {
                    ps.setLong(1, startScn);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            logs.add(rs.getString(1));
                        }
                    }
                }
                try (Statement st = miner.createStatement();
                     ResultSet rs = st.executeQuery(Q_CURRENT_REDO_MEMBERS)) {
                    while (rs.next()) {
                        logs.add(rs.getString(1));
                    }
                }
                assertThat(logs).as("redo/archive log list covering startScn..now").isNotEmpty();

                boolean first = true;
                for (String log : logs) {
                    String call = "BEGIN DBMS_LOGMNR.ADD_LOGFILE(?, "
                            + (first ? "DBMS_LOGMNR.NEW" : "DBMS_LOGMNR.ADDFILE") + "); END;";
                    try (CallableStatement cs = miner.prepareCall(call)) {
                        cs.setString(1, log);
                        cs.execute();
                    }
                    first = false;
                }
                try (CallableStatement cs = miner.prepareCall(
                        "BEGIN DBMS_LOGMNR.START_LOGMNR(STARTSCN => ?, OPTIONS =>"
                                + " DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG"
                                + " + DBMS_LOGMNR.COMMITTED_DATA_ONLY); END;")) {
                    cs.setLong(1, startScn);
                    cs.execute();
                }

                try {
                    // Measured column facts: V$LOGMNR_CONTENTS has no "CSN" column — SCN is
                    // the per-change SCN and CSCN the commit SCN (populated for every DML
                    // row under COMMITTED_DATA_ONLY). The resume watermark is CSCN: strict
                    // transaction-boundary semantics, mirroring the binlog XID discipline.
                    //
                    // CONTAINER FILTER (root cause fix, task-1 gate): when mined from
                    // CDB$ROOT, the view's own CON_ID column is ALWAYS 1 for every row (it
                    // reflects the querying container, not the row's origin) — filtering
                    // CON_ID = <pdbConId> therefore yields 0 rows. The column that marks the
                    // ORIGIN container is SRC_CON_ID (= DBA_PDBS.PDB_ID of the generating
                    // PDB), populated under DICT_FROM_ONLINE_CATALOG. The diagnostic dump
                    // below prints CON_ID vs SRC_CON_ID/SRC_CON_UID/SRC_CON_NAME for the
                    // SPIKE_T rows so any future regression is visible in surefire stdout.
                    // Diagnostic: print the CON_ID vs SRC_CON_ID distribution once so a future
                    // Oracle-version regression (column semantics change) is visible in stdout.
                    // Measured on gvenzl/oracle-free:23: every row shows CON_ID=1 (root view
                    // meta column) and SRC_CON_ID=3 (FREEPDB1 origin).
                    List<Map<String, Object>> dump = queryAll(miner,
                            "SELECT CON_ID, SRC_CON_ID, SRC_CON_NAME, COUNT(*) AS CNT"
                                    + " FROM V$LOGMNR_CONTENTS"
                                    + " WHERE CSCN > " + startScn
                                    + " AND TABLE_NAME = 'SPIKE_T'"
                                    + " AND OPERATION IN ('INSERT', 'UPDATE', 'DELETE')"
                                    + " GROUP BY CON_ID, SRC_CON_ID, SRC_CON_NAME");
                    for (Map<String, Object> r : dump) {
                        System.out.println("DBG CON_ID=" + r.get("CON_ID")
                                + " SRC_CON_ID=" + r.get("SRC_CON_ID")
                                + " SRC_CON_NAME=" + r.get("SRC_CON_NAME")
                                + " count=" + r.get("CNT"));
                    }
                    List<Map<String, Object>> rows = queryAll(miner,
                            "SELECT RS_ID, CSCN, SCN, XID, OPERATION, SEG_OWNER, TABLE_NAME, SQL_REDO, TIMESTAMP"
                                    + " FROM V$LOGMNR_CONTENTS"
                                    + " WHERE CSCN > " + startScn
                                    + " AND SRC_CON_ID = " + conId
                                    + " AND SEG_OWNER = 'SRC' AND TABLE_NAME = 'SPIKE_T'"
                                    + " AND OPERATION IN ('INSERT', 'UPDATE', 'DELETE')");

                    assertThat(redosOf(rows, "INSERT")).hasSize(3);
                    List<String> updates = redosOf(rows, "UPDATE");
                    List<String> deletes = redosOf(rows, "DELETE");
                    assertThat(updates).hasSize(1);
                    assertThat(deletes).hasSize(1);

                    // The online catalog resolved PDB identifiers from the root session.
                    String updateRedo = updates.get(0);
                    assertThat(updateRedo).contains("SPIKE_T").doesNotContain("OBJ#");

                    // ALL COLUMNS supplemental logging: the UPDATE after-image carries every column.
                    assertThat(updateRedo)
                            .contains("\"ID\"").contains("\"NAME\"")
                            .contains("\"NOTE\"").contains("\"TS\"")
                            .contains("更新后的备注");
                    assertThat(deletes.get(0)).contains("\"ID\"");

                    // Multibyte + quote escaping survive into SQL_REDO.
                    String insertRedos = String.join("\n", redosOf(rows, "INSERT"));
                    assertThat(insertRedos).contains("世界").contains("it''s here");

                    recordSamples(rows);
                } finally {
                    try (CallableStatement cs = miner.prepareCall(
                            "BEGIN DBMS_LOGMNR.END_LOGMNR; END;")) {
                        cs.execute();
                    } catch (SQLException ignored) {
                        // session already gone — nothing to clean up
                    }
                }
            }
        }
    }

    private void recordSamples(List<Map<String, Object>> rows) throws Exception {
        Path out = Paths.get("src/test/resources/logminer/samples-23ai-free.txt");
        Files.createDirectories(out.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("-- Real SQL_REDO recorded by LogMinerPathSpikeIT on gvenzl/oracle-free:23")
                .append(" (CDB mode: mined from CDB$ROOT as C##MINER,\n")
                .append("-- SRC_CON_ID-filtered to FREEPDB1; ALL COLUMNS supplemental logging,")
                .append(" DICT_FROM_ONLINE_CATALOG + COMMITTED_DATA_ONLY).\n")
                .append("-- Format: `-- OPERATION | RS_ID | CSCN` header line,")
                .append(" then the SQL_REDO verbatim. Fixture source for the Task 4 parser tests.\n");
        for (Map<String, Object> row : rows) {
            sb.append("-- ").append(row.get("OPERATION"))
                    .append(" | ").append(row.get("RS_ID"))
                    .append(" | ").append(row.get("CSCN")).append('\n');
            sb.append(row.get("SQL_REDO")).append('\n');
        }
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> redosOf(List<Map<String, Object>> rows, String operation) {
        return rows.stream()
                .filter(r -> operation.equals(r.get("OPERATION")))
                .map(r -> String.valueOf(r.get("SQL_REDO")))
                .collect(Collectors.toList());
    }

    private static long queryCurrentScn(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(Q_CURRENT_SCN)) {
            if (!rs.next()) {
                throw new IllegalStateException("V$DATABASE returned no row");
            }
            return rs.getLong(1);
        }
    }

    private static void dropQuietly(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE " + table + " PURGE");
        } catch (SQLException ignored) {
            // not created yet
        }
    }
}
