package com.qqmu.jync.service.cdc;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * The source-side YStream service that feeds one sync project.
 *
 * <p>A service is created, filled with tables, started and finally stopped and dropped
 * through {@code DBMS_YSTREAM_ADM}. Everything here is plain DDL-free plumbing: the
 * package calls only manipulate YStream's own catalog, never the captured tables — that is
 * why Jync may create a service without the user's (or a trigger's) footprint on the
 * replicated schema.
 *
 * <p>Measured conventions this class encodes:
 * <ul>
 *   <li>{@code ADD_TABLES} takes exactly three string arguments — service, a comma
 *       separated {@code schema.table} list, and a whole-schema list ({@code ''} when only
 *       the named tables are wanted). Two arguments are {@code YAS-04323}.</li>
 *   <li>Table names must be quoted. Yamashan keeps the dictionary spelling, so an
 *       unquoted lower-case table is folded to upper case and silently captures
 *       nothing.</li>
 *   <li>{@code STOP} is only valid on a started service; on a {@code CREATED} one it is
 *       the same {@code YAS-02731} that {@code DROP} gives for a started one. Release
 *       therefore looks at {@code STATUS} first.</li>
 *   <li>At most 32 services per instance, one client per service, primary only.</li>
 * </ul>
 */
@Slf4j
public final class YStreamServiceAdmin {

    /** Measured instance-wide ceiling for YStream services. */
    public static final int MAX_SERVICES = 32;

    /** Measured: a table entry is ~30–40 characters; 50 per call stays well inside limits. */
    static final int TABLES_PER_CALL = 50;

    static final String STATUS_CREATED = "CREATED";
    static final String STATUS_STARTED = "STARTED";
    static final String STATUS_RUNNING = "RUNNING";

    private static final String PREFIX = "JYNC_";
    private static final Pattern OURS = Pattern.compile("^JYNC_(\\d+)_[0-9A-F]{8}$");
    private static final String LIST_SERVICES =
            "SELECT SERVER_ID, SERVER_NAME, STATUS, START_SCN, RESTART_POSITION, ERROR"
                    + " FROM V$YSTREAM_SERVER ORDER BY SERVER_NAME";

    private YStreamServiceAdmin() {
    }

    /**
     * A row of {@code V$YSTREAM_SERVER}.
     *
     * @param serverId        the id {@code DBA_YSTREAM_TABLES} keys its members by
     * @param restartPosition the five-tuple the service would resume from, or null before
     *                        it has one
     */
    public record Service(long serverId, String name, String status, long startScn,
                          String restartPosition, String error) {

        public String effectiveStatus() {
            return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        }

        /** Whether a client may attach, or the service must be stopped before dropping. */
        public boolean started() {
            String s = effectiveStatus();
            return STATUS_STARTED.equals(s) || STATUS_RUNNING.equals(s);
        }
    }

    /** {@code JYNC_<projectId>_<token>} — the token distinguishes our service from a rebuilt one. */
    public static String serviceName(long projectId, String token) {
        return PREFIX + projectId + "_" + token;
    }

    /** A fresh 8-character upper-case hex token, unique per project's service. */
    public static String newToken() {
        return String.format("%08X", java.util.concurrent.ThreadLocalRandom.current().nextInt());
    }

    /**
     * The project id encoded in one of our service names, or null for anything else.
     *
     * <p>The name is the only place the token lives — Jync stores no row for it — so this
     * parse decides whether a service found on the source is ours.
     */
    public static Long serviceFor(String name) {
        if (name == null) {
            return null;
        }
        Matcher m = OURS.matcher(name.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Every service on the instance, ours or not. */
    public static List<Service> listServices(Connection conn) throws SQLException {
        List<Service> services = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(LIST_SERVICES)) {
            while (rs.next()) {
                services.add(new Service(rs.getLong("SERVER_ID"), rs.getString("SERVER_NAME"),
                        rs.getString("STATUS"), rs.getLong("START_SCN"),
                        rs.getString("RESTART_POSITION"), rs.getString("ERROR")));
            }
        }
        return services;
    }

    /** The services belonging to {@code projectId} — normally one, but leftovers are possible. */
    public static List<Service> servicesFor(Connection conn, long projectId) throws SQLException {
        List<Service> mine = new ArrayList<>();
        for (Service service : listServices(conn)) {
            Long owner = serviceFor(service.name());
            if (owner != null && owner == projectId) {
                mine.add(service);
            }
        }
        return mine;
    }

    /** How many of the instance's 32 service slots are used. */
    public static int serviceCount(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM V$YSTREAM_SERVER")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** The tables currently in the service's capture scope, as {@code SCHEMA.TABLE}. */
    public static Set<String> capturedTables(Connection conn, String serviceName) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        String sql = "SELECT SCHEMA, TABLE_NAME FROM DBA_YSTREAM_TABLES"
                + " WHERE SERVER_ID = (SELECT SERVER_ID FROM V$YSTREAM_SERVER"
                + " WHERE SERVER_NAME = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serviceName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String schema = rs.getString("SCHEMA");
                    String table = rs.getString("TABLE_NAME");
                    // A whole-schema membership is an empty TABLE_NAME row; we never ask
                    // for one, but treating it as a table would be wrong, so skip it.
                    if (table != null && !table.isEmpty()) {
                        tables.add(schema + "." + table);
                    }
                }
            }
        }
        return tables;
    }

    public static void createService(Connection conn, String serviceName) throws SQLException {
        call(conn, "{call DBMS_YSTREAM_ADM.CREATE(?)}", serviceName);
    }

    /**
     * Adds tables to the service's capture scope, {@value #TABLES_PER_CALL} per call.
     *
     * <p>Only ever called to add: removing a table from a project's selection leaves it in
     * the service, and the consumer simply filters its rows out. Re-creating the service to
     * shrink the scope would force a re-baseline for every other table.
     *
     * @param qualifiedTables {@code SCHEMA.TABLE} in dictionary spelling (case preserved)
     */
    public static void addTables(Connection conn, String serviceName,
                                 List<String> qualifiedTables) throws SQLException {
        if (qualifiedTables == null || qualifiedTables.isEmpty()) {
            return;
        }
        List<String> quoted = new ArrayList<>(qualifiedTables.size());
        for (String table : qualifiedTables) {
            quoted.add(quoteQualified(table));
        }
        for (int from = 0; from < quoted.size(); from += TABLES_PER_CALL) {
            int to = Math.min(from + TABLES_PER_CALL, quoted.size());
            call(conn, "{call DBMS_YSTREAM_ADM.ADD_TABLES(?, ?, ?)}",
                    serviceName, String.join(",", quoted.subList(from, to)), "");
        }
    }

    public static void startService(Connection conn, String serviceName) throws SQLException {
        call(conn, "{call DBMS_YSTREAM_ADM.START(?)}", serviceName);
    }

    public static void stopService(Connection conn, String serviceName) throws SQLException {
        call(conn, "{call DBMS_YSTREAM_ADM.STOP(?)}", serviceName);
    }

    /**
     * Stops (only if started) and drops the service.
     *
     * <p>{@code STOP} on a {@code CREATED} service is itself an error, so the status is
     * checked first. A failed {@code STOP} is logged and the {@code DROP} still attempted —
     * if the service cannot be stopped it almost certainly cannot be dropped either, and
     * the caller's retry loop decides what to do.
     */
    public static void dropService(Connection conn, Service service) throws SQLException {
        if (service.started()) {
            try {
                stopService(conn, service.name());
            } catch (SQLException e) {
                log.warn("YStream service {} could not be stopped before DROP: {}",
                        service.name(), e.getMessage());
            }
        }
        call(conn, "{call DBMS_YSTREAM_ADM.DROP(?)}", service.name());
    }

    /**
     * Best-effort release of every service this project owns on {@code conn}.
     *
     * <p>Mirrors the PG slot release: deleting a project, switching away from YStream or
     * moving a project to another source must not leave a service behind that keeps
     * retaining source-side state for a consumer that will never come back. Never throws —
     * an unreachable source must not block the local operation — and each failure is
     * logged so an operator can clean up by hand.
     */
    public static void releaseQuietly(Connection conn, long projectId) {
        if (conn == null) {
            return;
        }
        List<Service> services;
        try {
            services = servicesFor(conn, projectId);
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not list YStream services of project {} to release them: {}",
                    projectId, e.getMessage());
            return;
        }
        for (Service service : services) {
            try {
                dropService(conn, service);
                log.info("Released YStream service {} of project {}", service.name(), projectId);
            } catch (SQLException | RuntimeException e) {
                log.warn("Could not release YStream service {} of project {} — drop it by"
                        + " hand: {}", service.name(), projectId, e.getMessage());
            }
        }
    }

    /** {@code SCHEMA.TABLE} → {@code "SCHEMA"."TABLE"}, doubling any embedded quote. */
    static String quoteQualified(String qualified) {
        int dot = qualified.indexOf('.');
        if (dot < 0) {
            return quote(qualified);
        }
        return quote(qualified.substring(0, dot)) + "." + quote(qualified.substring(dot + 1));
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void call(Connection conn, String sql, String... args) throws SQLException {
        try (CallableStatement cs = conn.prepareCall(sql)) {
            for (int i = 0; i < args.length; i++) {
                cs.setString(i + 1, args[i]);
            }
            cs.execute();
        }
    }
}
