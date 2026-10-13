package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * B2 capability probe: itemized checks against a MySQL source, all through plain JDBC.
 * Runs inside the existing connection test (connection page) and per project
 * (strategy card + CDC status panel). Never throws for a merely-unready source; SQL
 * errors on individual checks degrade to a failed check with the error as the detail.
 */
@Component
@Slf4j
public class BinlogCdcProbe {

    /** Retention below this is a trap: one long weekend purges the position away. */
    private static final long MIN_RETENTION_SECONDS = 24 * 3600L;

    public CdcProbeResult probe(Connection conn, long plannedServerId) {
        List<CdcProbeResult.Check> checks = new ArrayList<>();
        checks.add(checkVersion(conn));
        checks.add(checkVariable(conn, CdcProbeResult.LOG_BIN, "log_bin",
                v -> "ON".equalsIgnoreCase(v), "ON", "log_bin is %s — binary logging is off"));
        checks.add(checkVariable(conn, CdcProbeResult.BINLOG_FORMAT, "binlog_format",
                v -> "ROW".equalsIgnoreCase(v), "ROW", "binlog_format is %s — row events need ROW"));
        checks.add(checkVariable(conn, CdcProbeResult.BINLOG_ROW_IMAGE, "binlog_row_image",
                v -> "FULL".equalsIgnoreCase(v), "FULL",
                "binlog_row_image is %s — CDC applies full after-images and needs FULL"));
        checks.add(checkServerId(conn, plannedServerId));
        checks.add(checkGrants(conn));
        checks.add(checkBinlogStatus(conn));
        checks.add(checkRetention(conn));
        return CdcProbeResult.of(checks);
    }

    /**
     * P1-5: really read the current binlog position — the exact statement pair the
     * baseline uses ({@link CdcBaselineService}: SHOW BINARY LOG STATUS on 8.2+,
     * SHOW MASTER STATUS on older servers; 8.4 removed the latter). Passing proves
     * REPLICATION CLIENT actually works — SHOW GRANTS text matching can be fooled by
     * role grants — and that the position read the stream start depends on will succeed.
     */
    private CdcProbeResult.Check checkBinlogStatus(Connection conn) {
        SQLException last = null;
        for (String sql : new String[]{"SHOW BINARY LOG STATUS", "SHOW MASTER STATUS"}) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                if (rs.next()) {
                    String file = rs.getString("File");
                    long pos = rs.getLong("Position");
                    if (file != null && !file.isBlank()) {
                        return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_STATUS, true,
                                file + ":" + pos + " readable");
                    }
                }
            } catch (SQLException e) {
                last = e; // 8.0-: SHOW BINARY LOG STATUS 不存在,落到下一条
            }
        }
        return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_STATUS, false,
                last != null ? String.valueOf(last.getMessage())
                        : "could not read the binary log position (is log_bin enabled?)");
    }

    private CdcProbeResult.Check checkVersion(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            String version = conn.getMetaData().getDatabaseProductVersion();
            String banner = product + " " + version;
            if (version != null && version.toUpperCase(Locale.ROOT).contains("MARIADB")) {
                return CdcProbeResult.Check.of(CdcProbeResult.VERSION, false,
                        "MariaDB detected (" + banner + ") — its binlog dialect is out of scope");
            }
            int major = leadingNumber(version);
            int minor = secondNumber(version);
            boolean ok = major > 5 || (major == 5 && minor >= 7);
            return CdcProbeResult.Check.of(CdcProbeResult.VERSION, ok,
                    ok ? banner : banner + " — MySQL 5.7 or newer required");
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.VERSION, false, String.valueOf(e.getMessage()));
        }
    }

    private CdcProbeResult.Check checkVariable(Connection conn, String checkName, String variable,
                                               Predicate<String> ok, String expected, String failFormat) {
        try {
            String value = showVariable(conn, variable);
            if (value == null) {
                return CdcProbeResult.Check.of(checkName, false,
                        String.format(failFormat, "<unknown>"));
            }
            boolean pass = ok.test(value);
            return CdcProbeResult.Check.of(checkName, pass,
                    pass ? expected : String.format(failFormat, value));
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(checkName, false, String.valueOf(e.getMessage()));
        }
    }

    private CdcProbeResult.Check checkServerId(Connection conn, long plannedServerId) {
        try {
            String raw = showVariable(conn, "server_id");
            long value = raw == null ? 0L : Long.parseLong(raw.trim());
            if (value <= 0) {
                return CdcProbeResult.Check.of(CdcProbeResult.SERVER_ID, false,
                        "server_id is " + raw + " — replication needs a non-zero id");
            }
            if (plannedServerId > 0 && value == plannedServerId) {
                return CdcProbeResult.Check.of(CdcProbeResult.SERVER_ID, false,
                        "source server_id " + value + " collides with the id Jync would present ("
                                + plannedServerId + ") — change sync.cdc.server-id-offset");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.SERVER_ID, true, "server_id=" + value);
        } catch (SQLException | NumberFormatException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.SERVER_ID, false, String.valueOf(e.getMessage()));
        }
    }

    private CdcProbeResult.Check checkGrants(Connection conn) {
        boolean slave = false;
        boolean client = false;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SHOW GRANTS")) {
            while (rs.next()) {
                String grant = rs.getString(1).toUpperCase(Locale.ROOT);
                boolean global = grant.contains("ON *.*");
                boolean all = grant.contains("ALL PRIVILEGES") && global;
                if (all || grant.contains("REPLICATION SLAVE")) {
                    slave = true;
                }
                if (all || grant.contains("REPLICATION CLIENT")) {
                    client = true;
                }
            }
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.REPLICATION_GRANTS, false,
                    String.valueOf(e.getMessage()));
        }
        if (slave && client) {
            return CdcProbeResult.Check.of(CdcProbeResult.REPLICATION_GRANTS, true,
                    "REPLICATION SLAVE + REPLICATION CLIENT");
        }
        List<String> missing = new ArrayList<>();
        if (!slave) {
            missing.add("REPLICATION SLAVE");
        }
        if (!client) {
            missing.add("REPLICATION CLIENT");
        }
        return CdcProbeResult.Check.of(CdcProbeResult.REPLICATION_GRANTS, false,
                "missing grant(s): " + String.join(", ", missing));
    }

    private CdcProbeResult.Check checkRetention(Connection conn) {
        try {
            String secondsRaw = showVariable(conn, "binlog_expire_logs_seconds"); // MySQL 8
            long seconds = -1;
            String detail;
            if (secondsRaw != null && !secondsRaw.trim().isEmpty()) {
                seconds = Long.parseLong(secondsRaw.trim());
                detail = "binlog_expire_logs_seconds=" + seconds;
            } else {
                String daysRaw = showVariable(conn, "expire_logs_days"); // MySQL 5.7
                if (daysRaw == null || daysRaw.trim().isEmpty()) {
                    return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_RETENTION, false,
                            "could not read binlog_expire_logs_seconds / expire_logs_days");
                }
                long days = Long.parseLong(daysRaw.trim());
                seconds = days * 86_400L;
                detail = "expire_logs_days=" + days;
            }
            if (seconds == 0) {
                return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_RETENTION, true,
                        detail + " (never auto-purged — watch disk usage)");
            }
            if (seconds < MIN_RETENTION_SECONDS) {
                return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_RETENTION, false,
                        detail + " — keep at least " + MIN_RETENTION_SECONDS
                                + "s so an outage window cannot purge the position away");
            }
            return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_RETENTION, true,
                    detail + " (keep retention longer than the longest planned outage)");
        } catch (SQLException | NumberFormatException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_RETENTION, false,
                    String.valueOf(e.getMessage()));
        }
    }

    private static String showVariable(Connection conn, String name) throws SQLException {
        // 名字来自本类内部常量,不接收用户输入,拼接安全
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE '" + name + "'")) {
            return rs.next() ? rs.getString(2) : null;
        }
    }

    private static int leadingNumber(String version) {
        StringBuilder digits = new StringBuilder();
        for (char c : String.valueOf(version).toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
    }

    private static int secondNumber(String version) {
        String v = String.valueOf(version);
        int first = -1;
        for (int i = 0; i < v.length(); i++) {
            if (Character.isDigit(v.charAt(i))) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return 0;
        }
        int dot = v.indexOf('.', first);
        if (dot < 0) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = dot + 1; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
    }
}
