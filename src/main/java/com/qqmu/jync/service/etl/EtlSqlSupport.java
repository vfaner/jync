package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.util.JdbcUtil;

/**
 * The few SQL-shape rules the precheck and the runner must agree on.
 *
 * <p>They are shared on purpose: a precheck that accepts what the run then rejects is worse than
 * no precheck, because it moves the customer's trust in the wrong direction.
 */
public final class EtlSqlSupport {

    /** Label used for the wrapper around the customer's extraction SQL. */
    static final String SUBQUERY_ALIAS = "jync_etl_src";

    /**
     * Targets are created and written unquoted, so a result label must survive folding.
     * Public because the MongoDB source applies the same rule to document field names.
     */
    public static final Pattern PLAIN_IDENTIFIER =
            Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$#]*");

    static final Pattern RESUME_KEY =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private EtlSqlSupport() {
    }

    static String stripTrailingSemicolon(String sql) {
        return JdbcUtil.stripTrailingSemicolon(sql);
    }

    /**
     * Wraps the extraction SQL so its columns can be read without returning rows.
     *
     * <p>The closing paren starts a new line on purpose: the customer's SQL may end in a
     * {@code --} line comment, and appended on the same line the comment would swallow the
     * paren, the alias and the zero-row condition.
     */
    static String zeroRowProbe(String sql) {
        return "SELECT * FROM (" + stripTrailingSemicolon(sql) + "\n) "
                + SUBQUERY_ALIAS + " WHERE 1 = 0";
    }

    static String qualified(DatabaseConfig targetConfig, String configuredTable) {
        String table = configuredTable.trim();
        if (table.contains(".")) {
            return table;
        }
        String schema = targetConfig.getSchemaName();
        if (schema != null && !schema.isBlank()) {
            return schema.trim() + "." + table;
        }
        return table;
    }

    /**
     * The key two configured targets are compared by: schema-qualified and case-folded.
     *
     * <p>Members created by a batch store the bare catalog name, but the task form lets an
     * operator write {@code SCHEMA.table} (and on Oracle/DB2 the same table can be spelled in
     * either case). Comparing the raw strings lets both spellings into one batch, where the
     * two members then overwrite each other's rows.
     */
    static String targetKey(DatabaseConfig targetConfig, String configuredTable) {
        return qualified(targetConfig, configuredTable).toUpperCase(java.util.Locale.ROOT);
    }

    static boolean exists(Connection target, String qualifiedTable) throws SQLException {
        return exists(target, qualifiedTable, 0);
    }

    /**
     * @param timeoutSeconds {@code > 0} bounds the probe. A precheck must not wait forever
     *                       behind a metadata lock that the load itself would only meet after
     *                       an operator has already decided to run it.
     * @throws SQLException any failure other than "no such table". A permission error, a
     *                      lock timeout or a dead connection is not the same fact as an
     *                      absent table: answering {@code false} would send the caller into
     *                      CREATE TABLE over an object it may not even be allowed to see.
     */
    static boolean exists(Connection target, String qualifiedTable, int timeoutSeconds)
            throws SQLException {
        try (Statement probe = target.createStatement()) {
            if (timeoutSeconds > 0) {
                probe.setQueryTimeout(timeoutSeconds);
            }
            probe.executeQuery("SELECT * FROM " + qualifiedTable + " WHERE 1 = 0").close();
            return true;
        } catch (SQLException e) {
            // PostgreSQL-family drivers abort the whole transaction when any statement fails.
            // The probe for a missing table is expected to fail, so clear that state before
            // the caller's CREATE TABLE; nothing has been written to the target at this point.
            rollbackQuietly(target);
            if (isMissingTable(e)) {
                return false;
            }
            throw e;
        }
    }

    /**
     * The "no such table/view" spelling of every target family.
     *
     * <p>SQLSTATE alone is not enough: Oracle and DM keep SQLSTATE 42000 for everything and
     * distinguish by vendor code, while DB2 and SQL Server use their own states.
     */
    private static boolean isMissingTable(SQLException failure) {
        for (SQLException e = failure; e != null;
                e = e.getNextException() == e ? null : e.getNextException()) {
            String state = e.getSQLState();
            int code = e.getErrorCode();
            if ("42S02".equals(state)      // MySQL, H2 (non-empty db), SQL Server, DM
                    || "42S04".equals(state) // H2: table not found because the database is empty
                    || "42P01".equals(state) // PostgreSQL family
                    || "S0002".equals(state) // SQL Server
                    || "42704".equals(state)) { // DB2
                return true;
            }
            if (code == 1146      // MySQL ER_NO_SUCH_TABLE
                    || code == 942 // Oracle/DM ORA-00942
                    || code == 208 // SQL Server invalid object name
                    || code == -204 // DB2 undefined name
                    || code == 42102 // H2 table not found
                    || code == 42104) { // H2 table not found in an empty database
                return true;
            }
            String message = e.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                for (String marker : MISSING_TABLE_MARKERS) {
                    if (lower.contains(marker)) {
                        return true;
                    }
                }
                if (TABLE_DOES_NOT_EXIST.matcher(lower).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The messages of the analytic engines and HANA, none of which carry a state the list
     * above knows: ClickHouse returns code 60 but so does Oracle's ORA-00060 deadlock, which
     * is why the vendor codes are not extended here. A table probe cannot return these
     * messages for a permission fault — every family words that differently — so a match
     * means the object is absent.
     */
    private static final String[] MISSING_TABLE_MARKERS = {
            "table not found", "no such table", "unknown table",
            "invalid object name", "invalid table name", "invalid table or view name",
            "无效的表或视图名", "表或视图不存在"};

    /**
     * The analytic engines' "&lt;table&gt; does not exist" wording.
     *
     * <p>The noun must sit right next to the phrase: Hive says "Database does not exist"
     * and ClickHouse "Database foo doesn't exist", and reading either as a missing table
     * sends the run into CREATE TABLE over a schema that is not there.
     */
    private static final Pattern TABLE_DOES_NOT_EXIST = Pattern.compile(
            "(?:table|relation|view|object|entity|collection|表|视图)"
                    + "[^\\n]{0,80}?(?:does not exist|doesn't exist|不存在)",
            Pattern.CASE_INSENSITIVE);

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Auto-commit connections refuse a rollback; there is nothing to undo.
        }
    }
}
