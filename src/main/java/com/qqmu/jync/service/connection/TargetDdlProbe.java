package com.qqmu.jync.service.connection;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.cdc.CdcProbeResult;

import lombok.extern.slf4j.Slf4j;

/**
 * Target-side pre-flight, the counterpart of the source CDC probes: can the configured
 * account actually create a table where the sync will write?
 *
 * <p>Motivation — a live MySQL→Oracle run died at DDL time with
 * {@code CREATE TABLE "C##JYNC_MINER"."CLASS" (...) -> ORA-01031: insufficient privileges}.
 * The connection test was green because a TARGET connection deliberately skips the source
 * CDC probes, so nothing had checked write privileges. The account was the LogMiner mining
 * account, which is granted only what mining needs.
 *
 * <p><b>Why SESSION_PRIVS.</b> It reports the privileges <i>enabled in this session</i>,
 * which is what decides whether a statement runs. Measured on Oracle 23ai Free: a
 * {@code C##} common user granted {@code CREATE TABLE} in {@code CDB$ROOT} still reports 0
 * inside a PDB and still fails the real {@code CREATE TABLE} with ORA-01031, while
 * {@code DBA_SYS_PRIVS} would have reported the grant and produced a false pass. Walking
 * the catalog views would also have to follow role nesting by hand; {@code SESSION_PRIVS} is
 * already flattened.
 *
 * <p><b>Not a probe that creates anything.</b> Trying the real {@code CREATE TABLE} and
 * dropping it would be exact, but it writes DDL into the user's target schema, and for a
 * cross-schema target the cleanup needs {@code DROP ANY TABLE} — measured: a user holding
 * only {@code CREATE ANY TABLE} can create the probe table in the other schema and then
 * cannot drop it, leaving residue behind on every check.
 *
 * <p>Silent unless certain: a non-Oracle product (the look-alike databases accept ordinary
 * DDL fine and have no {@code SESSION_PRIVS}), or a dictionary query that itself fails,
 * yields no check at all rather than an accusation the account may not deserve.
 */
@Service
@Slf4j
public class TargetDdlProbe {

    /** Creating into the connection's own schema. */
    private static final String CREATE_TABLE = "CREATE TABLE";

    /** Creating into someone else's schema — what Oracle actually demands there. */
    private static final String CREATE_ANY_TABLE = "CREATE ANY TABLE";

    /**
     * Runs the probe, or reports nothing when it cannot answer with certainty.
     *
     * @return zero or one check; empty for a non-Oracle product or when the dictionary
     *     query fails — never a verdict inferred from a failed probe
     */
    public List<CdcProbeResult.Check> probe(Connection conn, DatabaseConfig config) {
        try {
            if (!isLiteralOracle(conn)) {
                return List.of();
            }
            String user = currentUser(conn);
            if (user == null) {
                return List.of();
            }
            String schema = config == null || !StringUtils.hasText(config.getSchemaName())
                    ? null : config.getSchemaName().trim();
            // Compared case-insensitively: the Oracle family folds a configured schema to upper
            // case before use, so a form value of "jync_tgt" is the connection user, not a
            // second schema that would demand CREATE ANY TABLE from an account that never
            // needed it.
            boolean ownSchema = schema == null || schema.equalsIgnoreCase(user);
            String required = ownSchema ? CREATE_TABLE : CREATE_ANY_TABLE;
            boolean granted = hasSessionPrivilege(conn, required);
            String detail = granted
                    ? describeGranted(user, ownSchema ? user : schema, required)
                    : describeMissing(user, schema, required, ownSchema);
            return List.of(CdcProbeResult.Check.of(
                    CdcProbeResult.TARGET_DDL_PRIVILEGES, granted, detail));
        } catch (SQLException e) {
            log.debug("Target DDL privilege probe could not run: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isLiteralOracle(Connection conn) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName();
        return product != null && product.toUpperCase(Locale.ROOT).contains("ORACLE");
    }

    private static String currentUser(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT USER FROM DUAL")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * True when the privilege is enabled right now. The privilege name is one of the two
     * constants above — no caller input reaches this SQL.
     */
    private static boolean hasSessionPrivilege(Connection conn, String privilege)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE = '" + privilege + "'";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() && rs.getLong(1) > 0;
        }
    }

    private static String describeGranted(String user, String schema, String privilege) {
        return "connection account " + user + " holds " + privilege + " — sync can create tables"
                + " in " + schema;
    }

    private static String describeMissing(String user, String schema, String privilege,
                                          boolean ownSchema) {
        StringBuilder sb = new StringBuilder();
        sb.append("connection account ").append(user).append(" does not hold ").append(privilege)
                .append(" in this session, so creating the target table would fail with")
                .append(" ORA-01031: insufficient privileges. ");
        if (ownSchema) {
            sb.append("Grant with: GRANT CREATE TABLE, CREATE SEQUENCE, CREATE TRIGGER TO ")
                    .append(user).append(";");
        } else {
            sb.append("The target schema is ").append(schema)
                    .append(", which is not the connection account's own, so Oracle requires")
                    .append(" CREATE ANY TABLE. Grant with: GRANT CREATE ANY TABLE TO ")
                    .append(user).append(";");
        }
        sb.append(" then ALTER USER ").append(user).append(" QUOTA UNLIMITED ON USERS;");
        if (isCommonUser(user)) {
            // Measured: a grant made in CDB$ROOT does not take effect inside a PDB.
            sb.append(" Note: ").append(user).append(" is a CDB common user (C##) — a common")
                    .append(" user's grants must be issued inside the target PDB:")
                    .append(" ALTER SESSION SET CONTAINER = <pdb>; before the GRANT.");
        }
        return sb.toString();
    }

    /** Oracle requires common users to carry the common-user prefix (C## by default). */
    private static boolean isCommonUser(String user) {
        return user != null && user.toUpperCase(Locale.ROOT).startsWith("C##");
    }
}
