package com.qqmu.jync.service.etl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.DatabaseType.DialectFamily;

/**
 * The statements an {@link com.qqmu.jync.model.EtlWriteMode#UPSERT} run needs on the target.
 *
 * <p>Unlike the real-time sync engine this deliberately does not go through the dialect's
 * upsert API: ETL creates and writes the target table with unquoted identifiers, so both the
 * DDL and every DML statement must fold the same way. A dialect-built {@code "dst"} would not
 * reach the unquoted {@code dst} on products that fold to upper case (Oracle, Dameng).
 *
 * <p>Only the MySQL family has a portable single-statement form. Every other target gets the
 * universal update-then-insert emulation the sync engine also uses ({@code UPDATE ... WHERE
 * key}; insert when no row matched).
 */
final class EtlUpsertSql {

    private EtlUpsertSql() {
    }

    /** Splits the configured comma-separated key list; blanks are dropped. */
    static List<String> parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String part : configured.split(",")) {
            String key = part.trim();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Maps every configured key onto the result's column label, ignoring case.
     *
     * @throws IllegalArgumentException naming the first key the result does not carry
     */
    static List<String> resolve(List<String> resultColumns, List<String> keys) {
        List<String> resolved = new ArrayList<>(keys.size());
        for (String key : keys) {
            String label = null;
            for (String column : resultColumns) {
                if (column.equalsIgnoreCase(key)) {
                    label = column;
                    break;
                }
            }
            if (label == null) {
                throw new IllegalArgumentException(
                        "upsert key column not found in the extraction result: " + key);
            }
            resolved.add(label);
        }
        return resolved;
    }

    /** The keys that appear in neither the result labels nor their case-insensitive match. */
    static List<String> unresolved(List<String> resultColumns, List<String> keys) {
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            boolean found = resultColumns.stream().anyMatch(c -> c.equalsIgnoreCase(key));
            if (!found) {
                missing.add(key);
            }
        }
        return missing;
    }

    /**
     * One-statement upsert for the MySQL family, or null when the target has no portable form
     * (everything else falls back to update-then-insert).
     *
     * <p>Doris is excluded on purpose: it speaks the MySQL wire protocol but rejects
     * {@code ON DUPLICATE KEY UPDATE}.
     */
    static String nativeUpsert(DatabaseType targetType, String targetName,
                               List<String> columns, List<String> keyColumns) {
        if (targetType == null || targetType == DatabaseType.DORIS
                || targetType.getFamily() != DialectFamily.MYSQL || keyColumns.isEmpty()) {
            return null;
        }
        String insert = "INSERT INTO " + targetName
                + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")";
        List<String> updatable = columns.stream()
                .filter(c -> !containsIgnoreCase(keyColumns, c))
                .collect(Collectors.toList());
        if (updatable.isEmpty()) {
            // A key-only table has nothing to change; the clause exists only to turn a
            // duplicate key from an error into a no-op.
            String key = keyColumns.get(0);
            return insert + " ON DUPLICATE KEY UPDATE " + key + " = " + key;
        }
        String assignments = updatable.stream()
                .map(c -> c + " = VALUES(" + c + ")")
                .collect(Collectors.joining(", "));
        return insert + " ON DUPLICATE KEY UPDATE " + assignments;
    }

    /** Update used by the emulation; null when every column is part of the key. */
    static String update(String targetName, List<String> columns, List<String> keyColumns) {
        List<String> updatable = columns.stream()
                .filter(c -> !containsIgnoreCase(keyColumns, c))
                .collect(Collectors.toList());
        if (updatable.isEmpty()) {
            return null;
        }
        String sets = updatable.stream().map(c -> c + " = ?").collect(Collectors.joining(", "));
        return "UPDATE " + targetName + " SET " + sets + " WHERE " + keyPredicate(keyColumns);
    }

    /** Existence probe that resolves the affected-row ambiguity of the emulation. */
    static String selectByKey(String targetName, List<String> keyColumns) {
        return "SELECT 1 FROM " + targetName + " WHERE " + keyPredicate(keyColumns);
    }

    private static String keyPredicate(List<String> keyColumns) {
        return keyColumns.stream().map(c -> c + " = ?").collect(Collectors.joining(" AND "));
    }

    private static boolean containsIgnoreCase(List<String> columns, String name) {
        for (String column : columns) {
            if (column.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
