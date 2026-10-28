package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * B2 capability probe: itemized checks against a MySQL-family source. Runs inside the
 * existing connection test (connection page) and per project (strategy card + CDC status
 * panel). Never throws for a merely-unready source; SQL errors on individual checks
 * degrade to a failed check with the error as the detail.
 *
 * <p>v1.9.0: the source may be any MySQL-wire product, not just the MySQL preset. The
 * SQL surface (variables, grants, position statements) is shared, so the JDBC checks
 * apply unchanged; what is <em>not</em> shared is the binlog wire format, which is why
 * a non-native product additionally has to decode one real event
 * ({@link CdcProbeResult#BINLOG_EVENT_READABLE}) before the probe passes.
 */
@Component
@Slf4j
public class BinlogCdcProbe {

    /** Retention below this is a trap: one long weekend purges the position away. */
    private static final long MIN_RETENTION_SECONDS = 24 * 3600L;

    /**
     * The fake replica id the capability probe presents. Deliberately above the project
     * range (server-id-offset + projectId) and the common real-replica range, so opening
     * a probe stream cannot evict a running Jync consumer or a real replica.
     */
    static final long PROBE_SERVER_ID = 4_294_967_294L;

    /** Connect + first-event budget; the reader may wait this long twice. */
    private static final Duration STREAM_PROBE_TIMEOUT = Duration.ofSeconds(4);

    /** Upper bound on SHOW BINARY LOGS rows read while looking for the previous file. */
    private static final int MAX_LISTED_BINLOGS = 1024;

    private final BinlogEventReader eventReader;

    public BinlogCdcProbe(BinlogEventReader eventReader) {
        this.eventReader = eventReader;
    }

    public CdcProbeResult probe(Connection conn, DatabaseConfig config, long plannedServerId) {
        BinlogPosition position = readPosition(conn);
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
        checks.add(checkBinlogStatus(position));
        checks.add(checkRetention(conn));
        checks.add(checkEventDecodable(conn, config, position));
        return CdcProbeResult.of(checks);
    }

    /** The current file/position, or the reason it could not be read. */
    private record BinlogPosition(String file, long pos, String error) {
    }

    /**
     * P1-5: really read the current binlog position — the exact statement pair the
     * baseline uses ({@link CdcBaselineService}: SHOW BINARY LOG STATUS on 8.2+,
     * SHOW MASTER STATUS on older servers; 8.4 removed the latter). Passing proves
     * REPLICATION CLIENT actually works — SHOW GRANTS text matching can be fooled by
     * role grants — and that the position read the stream start depends on will succeed.
     */
    private BinlogPosition readPosition(Connection conn) {
        SQLException last = null;
        for (String sql : new String[]{"SHOW BINARY LOG STATUS", "SHOW MASTER STATUS"}) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                if (rs.next()) {
                    String file = rs.getString("File");
                    long pos = rs.getLong("Position");
                    if (file != null && !file.isBlank()) {
                        return new BinlogPosition(file, pos, null);
                    }
                }
            } catch (SQLException e) {
                last = e; // 8.0-: SHOW BINARY LOG STATUS 不存在,落到下一条
            }
        }
        return new BinlogPosition(null, 4L, last != null ? String.valueOf(last.getMessage())
                : "could not read the binary log position (is log_bin enabled?)");
    }

    private CdcProbeResult.Check checkBinlogStatus(BinlogPosition position) {
        return position.file() != null
                ? CdcProbeResult.Check.of(CdcProbeResult.BINLOG_STATUS, true,
                        position.file() + ":" + position.pos() + " readable")
                : CdcProbeResult.Check.of(CdcProbeResult.BINLOG_STATUS, false, position.error());
    }

    private CdcProbeResult.Check checkVersion(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            String version = conn.getMetaData().getDatabaseProductVersion();
            String banner = product + " " + version;
            if (!isNativeMysql(product, version)) {
                // MariaDB / TiDB / OceanBase and the rest report their own banner or a
                // vendor suffix. A MySQL version floor means nothing to them: their
                // capability question is answered by the event-stream check below.
                return CdcProbeResult.Check.of(CdcProbeResult.VERSION, true,
                        banner + " — a MySQL-wire product; the binlog stream is verified below");
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

    /**
     * Whether this is Oracle MySQL itself rather than a MySQL-wire look-alike.
     *
     * <p>TiDB answers "MySQL" with a version like {@code 5.7.25-TiDB-v7.5.0}, so the
     * product name alone is not enough; the vendor marker in the version is the tell.
     */
    private static boolean isNativeMysql(String product, String version) {
        if (product == null || !"mysql".equalsIgnoreCase(product.trim())) {
            return false;
        }
        String v = String.valueOf(version).toLowerCase(Locale.ROOT);
        return !v.contains("mariadb") && !v.contains("tidb");
    }

    /**
     * Decode one event from a real stream on a non-native product.
     *
     * <p>Native MySQL skips it: its wire format is exercised by every running project.
     * For everything else this is the only check that can distinguish "MySQL-compatible
     * SQL" from "the rows events this client can actually read".
     */
    private CdcProbeResult.Check checkEventDecodable(Connection conn, DatabaseConfig config,
                                                     BinlogPosition position) {
        String product;
        String version;
        try {
            product = conn.getMetaData().getDatabaseProductName();
            version = conn.getMetaData().getDatabaseProductVersion();
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_EVENT_READABLE, false,
                    String.valueOf(e.getMessage()));
        }
        if (isNativeMysql(product, version)) {
            return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_EVENT_READABLE, true,
                    "native MySQL " + version + " — the production consumer exercises the stream");
        }
        if (config == null || position.file() == null) {
            return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_EVENT_READABLE, false,
                    "the binlog position is unreadable, so no stream can be opened");
        }
        String probeFile = probeFile(conn, position.file());
        try {
            String decoded = eventReader.readFirstEvent(config, probeFile, 4L,
                    PROBE_SERVER_ID, STREAM_PROBE_TIMEOUT);
            return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_EVENT_READABLE, true,
                    "decoded " + decoded + " from " + probeFile + ":4"
                            + " (probe server_id " + PROBE_SERVER_ID + ")");
        } catch (CdcDecodeException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_EVENT_READABLE, false,
                    message(e) + " — this product's binlog event format cannot be decoded by"
                            + " the MySQL CDC engine");
        } catch (Exception e) {
            // Credentials, grants, network, a silent stream: all real failures, none of
            // them evidence that the product's format is incompatible.
            return CdcProbeResult.Check.of(CdcProbeResult.BINLOG_EVENT_READABLE, false,
                    message(e) + " — the probe could not read the binlog stream");
        }
    }

    /**
     * The file the stream probe opens: the one just before the current binlog, when the
     * server lists one.
     *
     * <p>Measured on MariaDB 11.8: right after a rotation the current file holds only
     * FormatDescription, GTID_LIST and CHECKPOINT events — no row events at all. The
     * previous file is closed and full of recent DML, so reading from its start reaches a
     * TableMap/WriteRows event within a few events, which is the part of the wire format
     * that actually differs between MySQL-compatible products. Falls back to the current
     * file when it is the only one (fresh server) or when the list cannot be read (no
     * REPLICATION CLIENT) — the probe then still proves the framing decodes.
     */
    private String probeFile(Connection conn, String currentFile) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW BINARY LOGS")) {
            List<String> files = new ArrayList<>();
            // 有界:一份正常的 binlog 索引只有几百条,而一个永远返回 true 的结果集
            // 会让这里转不出去(损坏的驱动、代理层)。够用来找当前文件的前一份即可。
            int scanned = 0;
            while (rs.next() && scanned++ < MAX_LISTED_BINLOGS) {
                String name = rs.getString(1);
                if (name != null && !name.isBlank()) {
                    files.add(name.trim());
                }
            }
            int index = files.indexOf(currentFile);
            return index > 0 ? files.get(index - 1) : currentFile;
        } catch (SQLException e) {
            log.debug("SHOW BINARY LOGS unavailable ({}); probing the current file {}",
                    e.getMessage(), currentFile);
            return currentFile;
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
                // MySQL 8.0.22+ renamed REPLICATION SLAVE to REPLICATION REPLICA; 8.4
                // stopped accepting the old name entirely. Matching only SLAVE told a
                // correctly privileged account it was missing the grant.
                if (all || grant.contains("REPLICATION SLAVE")
                        || grant.contains("REPLICATION REPLICA")) {
                    slave = true;
                }
                // MariaDB 10.5 renamed REPLICATION CLIENT to BINLOG MONITOR; the old name
                // is still accepted by GRANT but SHOW GRANTS prints only the new one, so a
                // correctly privileged MariaDB account looked like it was missing the grant.
                if (all || grant.contains("REPLICATION CLIENT")
                        || grant.contains("BINLOG MONITOR")) {
                    client = true;
                }
            }
        } catch (SQLException e) {
            return CdcProbeResult.Check.of(CdcProbeResult.REPLICATION_GRANTS, false,
                    String.valueOf(e.getMessage()));
        }
        if (slave && client) {
            return CdcProbeResult.Check.of(CdcProbeResult.REPLICATION_GRANTS, true,
                    "REPLICATION REPLICA/SLAVE + REPLICATION CLIENT");
        }
        List<String> missing = new ArrayList<>();
        if (!slave) {
            missing.add("REPLICATION REPLICA (named REPLICATION SLAVE before MySQL 8.0.22)");
        }
        if (!client) {
            missing.add("REPLICATION CLIENT (named BINLOG MONITOR on MariaDB 10.5+)");
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

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
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
