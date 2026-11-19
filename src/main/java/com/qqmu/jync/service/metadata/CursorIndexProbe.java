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
 * The primary key counts as an index.
 *
 * <p>Asked in two places: the sync engine (whether the bookkeeping {@code COUNT(*)} probes
 * still run, and whether the run advises creating an index on the cursor column) and the
 * project page (which flags the table for the operator). Both need the same answer, and both
 * must treat an unreadable dictionary as "unknown" rather than "unindexed".
 *
 * <p>The MySQL family keeps the database name in the JDBC catalog and reports no schema, so
 * the two arguments swap — the same convention {@code DataSyncService} uses for the target
 * side's scale probe.
 *
 * <p>Some drivers do not list the primary key in {@link DatabaseMetaData#getIndexInfo}:
 * TDengine 3.3.6 returns no rows at all, even for the {@code ts} key every table has. When
 * the index dictionary reports nothing for the table, {@link DatabaseMetaData#getPrimaryKeys}
 * is asked instead, under the caller's catalog/schema convention and then under the swapped
 * one — the TDengine driver keys the key lookup on the catalog while its index lookup
 * answers nothing either way.
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
        // Oracle- and DB2-family dictionaries key unquoted identifiers upper case; a
        // lower-case configured schema matches no row, and the probe would report a table
        // that has the index as unindexed. PostgreSQL keeps whatever case it was given.
        String dictionarySchema = !mysqlFamily
                && (family == DialectFamily.ORACLE || family == DialectFamily.DB2)
                && schema != null && !schema.isBlank()
                        ? schema.toUpperCase(java.util.Locale.ROOT) : schema;
        String catalog = mysqlFamily ? schema : null;
        String indexSchema = mysqlFamily ? null : dictionarySchema;
        try {
            DatabaseMetaData md = conn.getMetaData();
            boolean sawRowForTable = false;
            try (ResultSet rs = md.getIndexInfo(catalog, indexSchema, table, false, true)) {
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
                    sawRowForTable = true;
                    if (rs.getInt("ORDINAL_POSITION") == 1
                            && indexedColumn.equalsIgnoreCase(column)) {
                        return Boolean.TRUE;
                    }
                }
            }
            if (sawRowForTable) {
                return Boolean.FALSE;
            }
            // An empty index dictionary does not mean "no index": the primary key is one,
            // and some drivers list it only through getPrimaryKeys.
            if (primaryKeyLeads(md, catalog, indexSchema, table, column)) {
                return Boolean.TRUE;
            }
            return primaryKeyLeads(md, indexSchema, catalog, table, column)
                    ? Boolean.TRUE : Boolean.FALSE;
        } catch (SQLException | RuntimeException e) {
            log.debug("Cannot read the indexes of {}: {}", table, e.toString());
            return null;
        }
    }

    /**
     * Whether {@code column} is the first column of the table's primary key, under one
     * catalog/schema convention. A throw propagates: an unreadable key dictionary is an
     * unknown answer, not an unindexed table.
     */
    private static boolean primaryKeyLeads(DatabaseMetaData md, String catalog,
                                           String schema, String table,
                                           String column) throws SQLException {
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                String keyColumn = rs.getString("COLUMN_NAME");
                if (keyColumn != null && rs.getShort("KEY_SEQ") == 1
                        && keyColumn.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }
}
