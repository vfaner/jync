package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v1.9.0: the LogMiner readiness probe on DM8.
 *
 * <p>Same itemized shape as the Oracle probe, different questions — and every one of them was
 * measured against a real DM8 (docs/dm-logminer-spike.md):
 * <ul>
 *   <li>archiving is {@code V$DATABASE.ARCH_MODE = 'Y'} (no {@code LOG_MODE} column);</li>
 *   <li>the per-table {@code (ALL) COLUMNS} group has no DM counterpart: the equivalent is the
 *       system parameter {@code RLOG_APPEND_LOGIC = 2}, which writes the full old image into
 *       redo; {@code 1} silently drops every unchanged column and breaks the upsert;</li>
 *   <li>only archived files can be mined, so nothing is mineable until a redo file is switched
 *       and archived — and the switch interval is the CDC latency floor (spike §1.2);</li>
 *   <li>DM has no LONG type and no CDB, so those Oracle checks do not exist here.</li>
 * </ul>
 */
class LogMinerCdcProbeDmTest {

    private final LogMinerCdcProbe probe = new LogMinerCdcProbe();
    private Connection conn;
    private Statement stmt;

    /** Levers each test flips; the rig answers the measured queries from these values. */
    private String product = "DM DBMS";
    private String version = "8.1.2.128";
    private String archMode = "Y";
    private String logicRedo = "2";
    private long archiveCount = 18;
    private long minChange = 38_827;
    private long maxChange = 44_776;
    private Timestamp newestArchive = new Timestamp(System.currentTimeMillis());
    private boolean archiveTableReadable = true;

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        stmt = mock(Statement.class);

        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn(product);
        when(md.getDatabaseProductVersion()).thenReturn(version);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.createStatement()).thenReturn(stmt);

        when(stmt.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            ResultSet rs = mock(ResultSet.class);
            if (sql.contains("ARCH_MODE")) {
                when(rs.next()).thenReturn(true);
                when(rs.getString(1)).thenReturn(archMode);
            } else if (sql.contains("RLOG_APPEND_LOGIC")) {
                when(rs.next()).thenReturn(true);
                when(rs.getString(1)).thenReturn(logicRedo);
            } else if (sql.startsWith("SELECT COUNT(*), MIN(")) {
                when(rs.next()).thenReturn(true);
                when(rs.getLong(1)).thenReturn(archiveCount);
                when(rs.getLong(2)).thenReturn(minChange);
                when(rs.getLong(3)).thenReturn(maxChange);
                when(rs.getTimestamp(4)).thenReturn(newestArchive);
            } else if (sql.contains("V$ARCHIVED_LOG")) {
                if (!archiveTableReadable) {
                    throw new SQLException("[-5504] No privilege to read V$ARCHIVED_LOG");
                }
                when(rs.next()).thenReturn(true);
                when(rs.getLong(1)).thenReturn(archiveCount);
            } else {
                // V$RLOGFILE (the DM dialect probe) and anything else: one row is enough.
                when(rs.next()).thenReturn(true);
                when(rs.getLong(1)).thenReturn(1L);
            }
            return rs;
        });
        // The Oracle dialect probe must fail first, or detection would call this Oracle.
        // doThrow, not when(): the catch-all stub above already answers this call, and a
        // when(...) re-stub would have to invoke it to record the matcher.
        doThrow(new SQLException("[-2111] Invalid column name [CURRENT_SCN]"))
                .when(stmt).executeQuery("SELECT CURRENT_SCN FROM V$DATABASE");
    }

    @Test
    void aProperlyConfiguredDmInstancePassesEveryDmCheck() {
        CdcProbeResult result = probe.probe(conn, List.of());

        assertThat(result.passed()).as(String.valueOf(result.failures())).isTrue();
        assertThat(result.getChecks()).extracting(CdcProbeResult.Check::getName)
                .containsExactly(CdcProbeResult.ORACLE_SOURCE, CdcProbeResult.VERSION,
                        CdcProbeResult.ARCHIVELOG, CdcProbeResult.DM_LOGIC_REDO,
                        CdcProbeResult.DM_ARCHIVE_PERIOD, CdcProbeResult.MINING_PRIVILEGES)
                .as("no Oracle-only checks: DM has no per-table supplemental group, no LONG,"
                        + " no CDB")
                .doesNotContain(CdcProbeResult.SUPPLEMENTAL_TABLES,
                        CdcProbeResult.LONG_COLUMNS, CdcProbeResult.CDB_MINING_MODE);
        assertThat(byName(result, CdcProbeResult.ARCHIVELOG).getDetail())
                .contains("ARCH_MODE=Y");
        assertThat(byName(result, CdcProbeResult.DM_ARCHIVE_PERIOD).getDetail())
                .contains("18 file(s)")
                .contains("38827")
                .contains("44776");
    }

    @Test
    void noArchiveModeFailsWithTheDmRemediation() {
        archMode = "N";

        CdcProbeResult result = probe.probe(conn, List.of());

        assertThat(result.passed()).isFalse();
        CdcProbeResult.Check check = byName(result, CdcProbeResult.ARCHIVELOG);
        assertThat(check.isPassed()).isFalse();
        assertThat(check.getDetail())
                .as("the fix is DBA-side and multi-step, so the check says all of it")
                .contains("dmarch.ini")
                .contains("ARCH_INI=1")
                .contains("ALTER SYSTEM SWITCH LOGFILE");
    }

    /**
     * {@code RLOG_APPEND_LOGIC} must be 2. At 1 the UPDATE's WHERE clause carries only the key,
     * the parser refuses the statement for missing columns, and the increment stalls — so this
     * is a hard failure, not a warning (spike §1.1).
     */
    @Test
    void logicRedoBelowTwoFailsInsteadOfDegradingTheOldImage() {
        logicRedo = "1";

        CdcProbeResult result = probe.probe(conn, List.of());

        CdcProbeResult.Check check = byName(result, CdcProbeResult.DM_LOGIC_REDO);
        assertThat(check.isPassed()).isFalse();
        assertThat(check.getDetail())
                .contains("RLOG_APPEND_LOGIC=1")
                .contains("SP_SET_PARA_VALUE(1,'RLOG_APPEND_LOGIC',2)")
                .contains("restart");
    }

    @Test
    void noArchivedRedoYetFailsBecauseDmCanOnlyMineArchivedFiles() {
        archiveCount = 0;
        newestArchive = null;

        CdcProbeResult result = probe.probe(conn, List.of());

        CdcProbeResult.Check check = byName(result, CdcProbeResult.DM_ARCHIVE_PERIOD);
        assertThat(check.isPassed()).isFalse();
        assertThat(check.getDetail())
                .contains("no archived redo")
                .contains("ALTER SYSTEM SWITCH LOGFILE");
    }

    /**
     * A stale newest archive is a pass-with-warning, not a failure: mining works, it is just
     * behind by however long the db is left between switches. The 256 MB default redo size
     * means an idle database may not switch for hours, so the operator has to schedule it.
     */
    @Test
    void aStaleNewestArchivePassesWithTheLatencyWarning() {
        newestArchive = new Timestamp(System.currentTimeMillis() - 3 * 60 * 60 * 1000L);

        CdcProbeResult result = probe.probe(conn, List.of());

        CdcProbeResult.Check check = byName(result, CdcProbeResult.DM_ARCHIVE_PERIOD);
        assertThat(check.isPassed()).isTrue();
        assertThat(check.getDetail())
                .contains("archived")
                .contains("ALTER SYSTEM SWITCH LOGFILE");
    }

    @Test
    void anUnreadableRedoCatalogueFailsThePrivilegeCheck() {
        archiveTableReadable = false;

        CdcProbeResult result = probe.probe(conn, List.of());

        CdcProbeResult.Check check = byName(result, CdcProbeResult.MINING_PRIVILEGES);
        assertThat(check.isPassed()).isFalse();
        assertThat(check.getDetail()).contains("V$ARCHIVED_LOG");
    }

    /**
     * The probe answers by capability, but "answers the Oracle redo probe" is not by itself a
     * licence to mine: 崖山 is measured in a later step, so it must be refused by name until
     * then rather than inherit DM's results.
     */
    @Test
    void aLookAlikeThatAnswersTheOracleProbeIsStillRefusedUntilMeasured() throws SQLException {
        product = "YashanDB";
        version = "23.2";
        // 崖山 answers the Oracle probe (no error), so detection returns the Oracle dialect.
        doAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getLong(1)).thenReturn(1_000L);
            return rs;
        }).when(stmt).executeQuery("SELECT CURRENT_SCN FROM V$DATABASE");

        CdcProbeResult result = probe.probe(conn, List.of());

        assertThat(result.passed()).isFalse();
        CdcProbeResult.Check source = byName(result, CdcProbeResult.ORACLE_SOURCE);
        assertThat(source.isPassed()).isFalse();
        assertThat(source.getDetail())
                .contains("YashanDB")
                .contains("Oracle")
                .contains("DM");
    }

    private static CdcProbeResult.Check byName(CdcProbeResult result, String name) {
        return result.getChecks().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " missing from "
                        + result.getChecks()));
    }
}
