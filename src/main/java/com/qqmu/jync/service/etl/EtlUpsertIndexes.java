package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the target enforces uniqueness on exactly the configured upsert key.
 *
 * <p>{@code INSERT ... ON DUPLICATE KEY UPDATE} only converges when a duplicate of the key
 * collides: without a unique index on exactly the key columns every run inserts another copy
 * of a row whose key the database cannot match. The single statement is therefore only used
 * against a table Jync created (and put the primary key on) or one that already carries such
 * an index; everything else falls back to the update-then-insert emulation.
 */
final class EtlUpsertIndexes {

    private static final Logger log = LoggerFactory.getLogger(EtlUpsertIndexes.class);

    private EtlUpsertIndexes() {
    }

    /**
     * Whether {@code targetName} has a unique index whose columns are exactly the keys.
     *
     * <p>Unreadable metadata answers "no": guessing "covered" duplicates rows, while the
     * emulation is always correct at the cost of one extra statement per row.
     */
    static boolean coversKeys(Connection target, String targetName, List<String> keyColumns) {
        if (keyColumns.isEmpty()) {
            return false;
        }
        String table = tableName(targetName);
        try {
            DatabaseMetaData metadata = target.getMetaData();
            // MySQL keeps the database in the JDBC catalog and reports no schema, so the
            // database belongs in the catalog argument (the same convention
            // DataSyncService and CursorIndexProbe use).
            String catalog = catalogName(targetName);
            if (catalog == null || catalog.isBlank()) {
                catalog = target.getCatalog();
            }
            Map<String, Set<String>> columnsByIndex = new HashMap<>();
            Set<String> withExpression = new HashSet<>();
            try (ResultSet rs = metadata.getIndexInfo(catalog, null, table, true, true)) {
                while (rs.next()) {
                    if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                        continue;
                    }
                    String indexName = rs.getString("INDEX_NAME");
                    if (indexName == null) {
                        continue;
                    }
                    String indexedTable = rs.getString("TABLE_NAME");
                    // The table-name argument is a LIKE pattern: asking for T_1 also returns
                    // TA1, whose indexes must not answer for T_1.
                    if (indexedTable != null && !indexedTable.equalsIgnoreCase(table)) {
                        continue;
                    }
                    if (rs.getBoolean("NON_UNIQUE")) {
                        continue;
                    }
                    String column = rs.getString("COLUMN_NAME");
                    // Null for a functional key part: an expression we cannot read is not
                    // proof that the configured keys are covered. Dropping only that part
                    // would leave a shorter set that can equal the keys — so the whole index
                    // is disqualified, not just the part.
                    if (column == null) {
                        withExpression.add(indexName);
                    } else if (isPrefixPart(rs.getString("SUB_PART"))) {
                        // UNIQUE KEY uk (email(32)) accepts a repeat of the suffix past the
                        // prefix: treating it as coverage lets the native upsert overwrite
                        // a row whose key differs only beyond the prefix.
                        withExpression.add(indexName);
                    } else {
                        columnsByIndex.computeIfAbsent(indexName, k -> new HashSet<>())
                                .add(column.toUpperCase(Locale.ROOT));
                    }
                }
            }
            Set<String> wanted = new HashSet<>();
            for (String key : keyColumns) {
                wanted.add(key.toUpperCase(Locale.ROOT));
            }
            // An unreadable unique index (expression, prefix) can collide on values outside
            // the configured keys, so it disqualifies the whole native statement: any conflict
            // it causes would update a row with a different key.
            if (!withExpression.isEmpty()) {
                return false;
            }
            boolean exact = false;
            for (Map.Entry<String, Set<String>> index : columnsByIndex.entrySet()) {
                if (index.getValue().equals(wanted)) {
                    // Exactly the keys: an equal key is guaranteed to collide (detection).
                    exact = true;
                } else if (!index.getValue().containsAll(wanted)) {
                    // MySQL raises ON DUPLICATE KEY on ANY unique index. An index that does
                    // not contain every key column can match a row whose key differs, and
                    // the statement would silently update that unrelated row.
                    return false;
                }
            }
            // A unique index on a strict superset cannot guarantee that equal keys collide
            // unless one index is exactly the keys.
            return exact;
        } catch (SQLException | RuntimeException e) {
            log.warn("Cannot read the unique indexes of {} ({}); using the emulated upsert",
                    targetName, e.toString());
            return false;
        }
    }

    /** Whether SUB_PART names a prefix length rather than the whole column. */
    private static boolean isPrefixPart(String subPart) {
        if (subPart == null || subPart.isBlank()) {
            return false;
        }
        try {
            return Long.parseLong(subPart.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The bare table name of a possibly schema-qualified identifier. */
    private static String tableName(String qualifiedName) {
        String name = qualifiedName == null ? "" : qualifiedName.trim();
        int dot = name.lastIndexOf('.');
        return unquote(dot < 0 ? name : name.substring(dot + 1).trim());
    }

    /** The schema of a possibly qualified identifier; null when it carries none. */
    private static String catalogName(String qualifiedName) {
        String name = qualifiedName == null ? "" : qualifiedName.trim();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : unquote(name.substring(0, dot).trim());
    }

    private static String unquote(String name) {
        if (name.length() >= 2) {
            char first = name.charAt(0);
            char last = name.charAt(name.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                return name.substring(1, name.length() - 1);
            }
        }
        return name;
    }
}
