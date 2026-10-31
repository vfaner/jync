package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qqmu.jync.model.DatabaseType.DialectFamily;

/**
 * Whether a column leads any index of a table — the only index shape a range read can use.
 *
 * <p>Asked in two places: the sync engine (whether the bookkeeping {@code COUNT(*)} probes
 * still run, and whether the run advises creating an index on the cursor column) and the
 * project page (which flags the table for the operator). Both need the same answer, and both
 * must treat an unreadable dictionary as "unknown" rather than "unindexed".
 *
 * <p>The MySQL family keeps the database name in the JDBC catalog and reports no schema, so
 * the two arguments swap — the same convention {@code DataSyncService} uses for the target
 * side's scale probe.
 */
public final class CursorIndexProbe {

    private static final Logger log = LoggerFactory.getLogger(CursorIndexProbe.class);

    private CursorIndexProbe() {
    }

    /**
     * @param family the source's SQL family, for the catalog/schema convention
     * @param schema the effective source schema (for MySQL, the database name)
     * @return {@code TRUE} when {@code column} is the first column of some index of the
     *         table, {@code FALSE} when no index leads with it, and {@code null} when the
     *         dictionary could not answer — callers must not read {@code null} as
     *         "unindexed".
     */
    public static Boolean columnLeadsIndex(Connection conn, DialectFamily family,
                                           String schema, String table, String column) {
        if (table == null || column == null) {
            // Nothing to ask about: not an answer, so not "unindexed" either.
            return null;
        }
        boolean mysqlFamily = family == DialectFamily.MYSQL;
        try {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getIndexInfo(mysqlFamily ? schema : null,
                    mysqlFamily ? null : schema, table, false, true)) {
                while (rs.next()) {
                    String indexedColumn = rs.getString("COLUMN_NAME");
                    if (indexedColumn == null) {
                        // A table-statistics row, not an index entry.
                        continue;
                    }
                    String indexedTable = rs.getString("TABLE_NAME");
                    // The table-name argument is a LIKE pattern: asking for T_1 also returns
                    // TA1, whose leading column must not answer for T_1.
                    if (indexedTable != null && !indexedTable.equalsIgnoreCase(table)) {
                        continue;
                    }
                    if (rs.getInt("ORDINAL_POSITION") == 1
                            && indexedColumn.equalsIgnoreCase(column)) {
                        return Boolean.TRUE;
                    }
                }
            }
            return Boolean.FALSE;
        } catch (SQLException | RuntimeException e) {
            log.debug("Cannot read the indexes of {}: {}", table, e.toString());
            return null;
        }
    }
}
