package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v1.9.0: the YStream readiness probe on YashanDB.
 *
 * <p>Every check encodes something measured on a live 23.4.7.100 (docs/dm-logminer-spike.md
 * §7, §8.1), and two of them exist because the failure is otherwise silent:
 * <ul>
 *   <li>a table without its {@code (ALL) COLUMNS} supplemental group delivers no DML at
 *       all — the service stays {@code RUNNING} and nothing is captured;</li>
 *   <li>without {@code SELECT ANY DICTIONARY} the admin views look like they do not exist
 *       while the client still connects, so the service would run unmanageable.</li>
 * </ul>
 */
class YStreamCdcProbeTest {

    private final YStreamCdcProbe probe = new YStreamCdcProbe();
    private Connection conn;
    private Statement stmt;

    private String product = "YashanDB";
    private boolean adminViewReadable = true;
    private String logMode = "ARCHIVELOG";
    private String databaseRole = "PRIMARY";
    private final Set<String> userPrivs = new LinkedHashSet<>(
            List.of("CREATE SESSION", "SELECT ANY TABLE", "YSTREAM_CAPTURE",
                    "SELECT ANY DICTIONARY"));
    private final java.util.Map<String, Set<String>> rolePrivs = new java.util.LinkedHashMap<>();
    private final Set<String> userRoles = new LinkedHashSet<>();
    private final Set<String> allLoggedTables = new LinkedHashSet<>();
    private final Set<String> primaryKeyTables = new LinkedHashSet<>();
    private boolean databaseAllLogged;
    private int serviceCount;

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        stmt = mock(Statement.class);

        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenAnswer(invocation -> product);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.createStatement()).thenReturn(stmt);

        when(stmt.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            ResultSet rs = mock(ResultSet.class);
            if (sql.contains("V$YSTREAM_SERVER")) {
                if (!adminViewReadable) {
                    throw new SQLException("[line: 1 column: 15] YAS-02012 table or view"
                            + " does not exist");
                }
                when(rs.next()).thenReturn(true);
                when(rs.getInt(1)).thenReturn(serviceCount);
            } else if (sql.contains("SUPPLEMENTAL_LOG_DATA_ALL")) {
                when(rs.next()).thenReturn(true);
                when(rs.getString(1)).thenReturn(databaseAllLogged ? "YES" : "NO");
            } else if (sql.contains("V$DATABASE")) {
                when(rs.next()).thenReturn(true);
                when(rs.getString(1)).thenReturn(logMode);
                when(rs.getString(2)).thenReturn(databaseRole);
            } else if (sql.contains("USER_SYS_PRIVS")) {
                rigSingleColumn(rs, userPrivs);
            } else if (sql.contains("ROLE_SYS_PRIVS")) {
                // Every grant on the instance, including roles this user does not hold —
                // the probe has to intersect with USER_ROLE_PRIVS itself.
                List<String[]> rows = new ArrayList<>();
                rolePrivs.forEach((role, privileges) -> {
                    for (String privilege : privileges) {
                        rows.add(new String[]{role, privilege});
                    }
                });
                rigRows(rs, rows);
            } else if (sql.contains("USER_ROLE_PRIVS")) {
                List<String[]> rows = new ArrayList<>();
                for (String role : userRoles) {
                    rows.add(new String[]{role});
                }
                rigRows(rs, rows);
            } else if (sql.contains("ALL_LOG_GROUPS")) {
                List<String[]> rows = new ArrayList<>();
                for (String table : allLoggedTables) {
                    rows.add(table.split("\\.", 2));
                }
                rigRows(rs, rows);
            } else if (sql.contains("ALL_CONSTRAINTS")) {
                List<String[]> rows = new ArrayList<>();
                for (String table : primaryKeyTables) {
                    rows.add(table.split("\\.", 2));
                }
                rigRows(rs, rows);
            }
            return rs;
        });
    }

    private static void rigSingleColumn(ResultSet rs, Set<String> values) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        for (String value : values) {
            rows.add(new String[]{value});
        }
        rigRows(rs, rows);
    }

    private static void rigRows(ResultSet rs, List<String[]> rows) throws SQLException {
        final int[] cursor = {-1};
        when(rs.next()).thenAnswer(inv -> ++cursor[0] < rows.size());
        when(rs.getString(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            String[] row = rows.get(cursor[0]);
            int column = inv.getArgument(0);
            return column <= row.length ? row[column - 1] : null;
        });
    }

    private static final List<String[]> TWO_TABLES = List.of(
            new String[]{"SYS", "T1"}, new String[]{"SYS", "T2"});

    @Test
    void aFullyReadySourcePassesEveryCheck() {
        allLoggedTables.addAll(List.of("SYS.T1", "SYS.T2"));
        primaryKeyTables.addAll(List.of("SYS.T1", "SYS.T2"));

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(result.passed()).as("%s", result.failures()).isTrue();
        assertThat(result.getChecks()).extracting(CdcProbeResult.Check::getName)
                .containsExactly(CdcProbeResult.YSTREAM_SOURCE, CdcProbeResult.YSTREAM_ARCHIVELOG,
                        CdcProbeResult.YSTREAM_PRIMARY, CdcProbeResult.YSTREAM_PRIVILEGES,
                        CdcProbeResult.YSTREAM_TABLE_LOGGING, CdcProbeResult.YSTREAM_PRIMARY_KEY,
                        CdcProbeResult.YSTREAM_SERVICE_CAPACITY);
    }

    @Test
    void aNonYashanProductIsTheWholeVerdict() {
        product = "Oracle";

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(result.getChecks()).hasSize(1);
        assertThat(result.getChecks().get(0).getName()).isEqualTo(CdcProbeResult.YSTREAM_SOURCE);
        assertThat(result.getChecks().get(0).isPassed()).isFalse();
        assertThat(result.getChecks().get(0).getDetail()).contains("Oracle");
    }

    @Test
    void anUnreadableViewAsksForSelectAnyDictionary() {
        // Measured: without it V$YSTREAM_SERVER / DBA_YSTREAM_TABLES report "does not exist"
        // while the YStream client still connects — a service that runs but cannot be managed.
        adminViewReadable = false;

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(result.getChecks()).hasSize(1);
        assertThat(result.getChecks().get(0).getName()).isEqualTo(CdcProbeResult.YSTREAM_SOURCE);
        assertThat(result.getChecks().get(0).getDetail())
                .contains("SELECT ANY DICTIONARY");
    }

    @Test
    void noarchivelogFails() {
        logMode = "NOARCHIVELOG";
        allLoggedTables.addAll(List.of("SYS.T1", "SYS.T2"));
        primaryKeyTables.addAll(List.of("SYS.T1", "SYS.T2"));

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(failure(result, CdcProbeResult.YSTREAM_ARCHIVELOG)).contains("NOARCHIVELOG");
    }

    @Test
    void aStandbyIsRefused() {
        databaseRole = "STANDBY";
        allLoggedTables.addAll(List.of("SYS.T1", "SYS.T2"));
        primaryKeyTables.addAll(List.of("SYS.T1", "SYS.T2"));

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(failure(result, CdcProbeResult.YSTREAM_PRIMARY)).contains("STANDBY");
    }

    @Test
    void missingPrivilegesNameBothRequiredGrants() {
        userPrivs.remove("SELECT ANY DICTIONARY");
        allLoggedTables.addAll(List.of("SYS.T1", "SYS.T2"));
        primaryKeyTables.addAll(List.of("SYS.T1", "SYS.T2"));

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        String detail = failure(result, CdcProbeResult.YSTREAM_PRIVILEGES);
        assertThat(detail).contains("SELECT ANY DICTIONARY");
        assertThat(detail).doesNotContain("YSTREAM_CAPTURE");
    }

    @Test
    void aPrivilegeHeldThroughAGrantedRoleCounts() {
        userPrivs.remove("YSTREAM_CAPTURE");
        userRoles.add("YSTREAM_ADMIN");
        rolePrivs.put("YSTREAM_ADMIN", Set.of("YSTREAM_CAPTURE"));
        allLoggedTables.addAll(List.of("SYS.T1", "SYS.T2"));
        primaryKeyTables.addAll(List.of("SYS.T1", "SYS.T2"));

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(result.passed()).as("%s", result.failures()).isTrue();
    }

    @Test
    void aRoleTheUserDoesNotHoldGrantsNothing() {
        userPrivs.remove("YSTREAM_CAPTURE");
        rolePrivs.put("UNHELD", Set.of("YSTREAM_CAPTURE")); // not in USER_ROLE_PRIVS
        allLoggedTables.addAll(List.of("SYS.T1", "SYS.T2"));
        primaryKeyTables.addAll(List.of("SYS.T1", "SYS.T2"));

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(failure(result, CdcProbeResult.YSTREAM_PRIVILEGES)).contains("YSTREAM_CAPTURE");
    }

    @Test
    void missingSupplementalLoggingListsTheTablesAndTheExactScript() {
        // Only T1 is logged; T2 is silently invisible to the stream until the DBA runs it.
        allLoggedTables.add("SYS.T1");
        primaryKeyTables.addAll(List.of("SYS.T1", "SYS.T2"));

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        String detail = failure(result, CdcProbeResult.YSTREAM_TABLE_LOGGING);
        assertThat(detail).contains("SYS.T2");
        assertThat(detail).contains(
                "ALTER TABLE \"SYS\".\"T2\" ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;");
    }

    @Test
    void aLongListOfMissingTablesIsTruncated() {
        List<String[]> many = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            many.add(new String[]{"SYS", "T" + i});
            primaryKeyTables.add("SYS.T" + i);
        }

        CdcProbeResult result = probe.probe(conn, many);

        String detail = failure(result, CdcProbeResult.YSTREAM_TABLE_LOGGING);
        assertThat(detail).contains("SYS.T1").contains("SYS.T5");
        assertThat(detail).doesNotContain("SYS.T6");
        assertThat(detail).contains("and 2 more");
    }

    @Test
    void databaseWideAllColumnsLoggingDoesNotCoverATable() {
        // Measured on 23.4.7.100: with ALTER DATABASE ... SUPPLEMENTAL LOG DATA (ALL)
        // COLUMNS on and no table-level group, the table's DML never enters the stream
        // (the service stays RUNNING and nothing is captured), so the database-wide flag
        // must not satisfy this check.
        databaseAllLogged = true;
        primaryKeyTables.addAll(List.of("SYS.T1", "SYS.T2"));

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(failure(result, CdcProbeResult.YSTREAM_TABLE_LOGGING))
                .contains("SYS.T1").contains("SYS.T2")
                .contains("ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
    }

    @Test
    void aTableWithoutAPrimaryKeyFails() {
        allLoggedTables.addAll(List.of("SYS.T1", "SYS.T2"));
        primaryKeyTables.add("SYS.T1");

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(failure(result, CdcProbeResult.YSTREAM_PRIMARY_KEY)).contains("SYS.T2");
    }

    @Test
    void tableChecksAreSkippedWithoutASelection() {
        // The connection-test page probes before any project exists: no table list means
        // "unknown", not "no tables", and must not fail the report.
        CdcProbeResult result = probe.probe(conn, List.of());

        assertThat(result.passed()).as("%s", result.failures()).isTrue();
        assertThat(result.getChecks()).extracting(CdcProbeResult.Check::getName)
                .doesNotContain(CdcProbeResult.YSTREAM_TABLE_LOGGING,
                        CdcProbeResult.YSTREAM_PRIMARY_KEY);
    }

    @Test
    void theCapacityCheckReportsUsedOverLimit() {
        serviceCount = 31;
        allLoggedTables.addAll(List.of("SYS.T1", "SYS.T2"));
        primaryKeyTables.addAll(List.of("SYS.T1", "SYS.T2"));

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(result.getChecks()).filteredOn(
                        c -> CdcProbeResult.YSTREAM_SERVICE_CAPACITY.equals(c.getName()))
                .singleElement().satisfies(check -> {
                    assertThat(check.isPassed()).isTrue();
                    assertThat(check.getDetail()).contains("31").contains("32");
                });
    }

    @Test
    void aFullInstanceFailsWithTheCounts() {
        // Not a warning: a 33rd service cannot be created, and nothing retries into room.
        serviceCount = YStreamServiceAdmin.MAX_SERVICES;
        allLoggedTables.addAll(List.of("SYS.T1", "SYS.T2"));
        primaryKeyTables.addAll(List.of("SYS.T1", "SYS.T2"));

        CdcProbeResult result = probe.probe(conn, TWO_TABLES);

        assertThat(failure(result, CdcProbeResult.YSTREAM_SERVICE_CAPACITY))
                .contains("32").contains("Release");
    }

    private static String failure(CdcProbeResult result, String name) {
        return result.failures().stream()
                .filter(c -> name.equals(c.getName()))
                .map(CdcProbeResult.Check::getDetail)
                .findFirst()
                .orElseThrow(() -> new AssertionError("check " + name + " did not fail: "
                        + result.getChecks()));
    }
}
