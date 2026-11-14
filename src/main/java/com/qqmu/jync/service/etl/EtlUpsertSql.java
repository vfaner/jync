package com.qqmu.jync.service.etl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.qqmu.jync.model.DatabaseType;

/**
 * The statements an {@link com.qqmu.jync.model.EtlWriteMode#UPSERT} run needs on the target.
 *
 * <p>Unlike the real-time sync engine this deliberately does not go through the dialect's
 * upsert API: ETL creates and writes the target table with unquoted identifiers, so both the
 * DDL and every DML statement must fold the same way. A dialect-built {@code "dst"} would not
 * reach the unquoted {@code dst} on products that fold to upper case (Oracle, Dameng).
 *
 * <p>MySQL, PostgreSQL (not openGauss/GaussDB), Oracle-family and SQL Server targets each have
 * a single-statement form, see {@link #nativeUpsert}. Everything else — DB2, HANA, Doris,
 * unknown targets — gets the universal update-then-insert emulation the sync engine also uses
 * ({@code UPDATE ... WHERE key}; insert when no row matched).
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
     * One-statement upsert for the target family, or null when there is no form the engine
     * trusts ({@code DB2}, {@code HANA}, unknown targets fall back to update-then-insert).
     *
     * <ul>
     *   <li>MySQL family: {@code ON DUPLICATE KEY UPDATE}. Doris is excluded on purpose: it
     *       speaks the MySQL wire protocol but rejects that clause.</li>
     *   <li>PostgreSQL family: {@code ON CONFLICT ... DO UPDATE}. openGauss / GaussDB are
     *       excluded for the same reason the sync engine excludes them — their default
     *       {@code sql_compatibility='A'} parser rejects {@code ON CONFLICT}.</li>
     *   <li>Oracle family (Oracle, Dameng, YashanDB): {@code MERGE} whose source is the bound
     *       row.</li>
     *   <li>SQL Server: {@code MERGE} over a {@code VALUES} row constructor, trailing
     *       semicolon.</li>
     * </ul>
     *
     * <p>All four forms converge only when the target enforces uniqueness on exactly the
     * configured keys; {@link EtlUpsertIndexes#coversKeys} decides that for a pre-existing
     * table before this statement is used.
     */
    static String nativeUpsert(DatabaseType targetType, String targetName,
                               List<String> columns, List<String> keyColumns) {
        if (targetType == null || keyColumns.isEmpty()) {
            return null;
        }
        switch (targetType.getFamily()) {
            case MYSQL:
                return targetType == DatabaseType.DORIS
                        ? null : mysqlUpsert(targetName, columns, keyColumns);
            case POSTGRES:
                // MySQL-flavoured on the compatibility-A parser (OpenGaussDialect's rule).
                if (targetType == DatabaseType.OPENGAUSS
                        || targetType == DatabaseType.GAUSSDB) {
                    return mysqlUpsert(targetName, columns, keyColumns);
                }
                return postgresUpsert(targetName, columns, keyColumns);
            case ORACLE:
                return oracleMerge(targetName, columns, keyColumns);
            case SQLSERVER:
                return sqlServerMerge(targetName, columns, keyColumns);
            default:
                return null;
        }
    }

    private static String mysqlUpsert(String targetName, List<String> columns,
                                      List<String> keyColumns) {
        String insert = insertValues(targetName, columns);
        List<String> updatable = nonKeyColumns(columns, keyColumns);
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

    private static String postgresUpsert(String targetName, List<String> columns,
                                         List<String> keyColumns) {
        String insert = insertValues(targetName, columns);
        List<String> updatable = nonKeyColumns(columns, keyColumns);
        String conflict = " ON CONFLICT (" + String.join(", ", keyColumns) + ") ";
        if (updatable.isEmpty()) {
            // Key-only table: DO NOTHING keeps a re-delivered key from erroring.
            return insert + conflict + "DO NOTHING";
        }
        String assignments = updatable.stream()
                .map(c -> c + " = EXCLUDED." + c)
                .collect(Collectors.joining(", "));
        return insert + conflict + "DO UPDATE SET " + assignments;
    }

    private static String oracleMerge(String targetName, List<String> columns,
                                      List<String> keyColumns) {
        String using = columns.stream()
                .map(c -> "? AS " + c)
                .collect(Collectors.joining(", "));
        String on = keyColumns.stream()
                .map(c -> "t." + c + " = s." + c)
                .collect(Collectors.joining(" AND "));
        List<String> updatable = nonKeyColumns(columns, keyColumns);
        StringBuilder sb = new StringBuilder("MERGE INTO ").append(targetName)
                .append(" t USING (SELECT ").append(using).append(" FROM DUAL) s ON (")
                .append(on).append(')');
        if (!updatable.isEmpty()) {
            sb.append(" WHEN MATCHED THEN UPDATE SET ").append(updatable.stream()
                    .map(c -> "t." + c + " = s." + c)
                    .collect(Collectors.joining(", ")));
        }
        sb.append(" WHEN NOT MATCHED THEN INSERT (").append(String.join(", ", columns))
                .append(") VALUES (").append(columns.stream()
                        .map(c -> "s." + c)
                        .collect(Collectors.joining(", ")))
                .append(')');
        return sb.toString();
    }

    private static String sqlServerMerge(String targetName, List<String> columns,
                                         List<String> keyColumns) {
        String values = "?";
        String aliased = String.join(", ", columns);
        String on = keyColumns.stream()
                .map(c -> "t." + c + " = s." + c)
                .collect(Collectors.joining(" AND "));
        List<String> updatable = nonKeyColumns(columns, keyColumns);
        StringBuilder sb = new StringBuilder("MERGE ").append(targetName)
                .append(" WITH (HOLDLOCK) AS t USING (VALUES (")
                .append(String.join(", ", java.util.Collections.nCopies(columns.size(), values)))
                .append(")) AS s (").append(aliased).append(") ON (").append(on).append(')');
        if (!updatable.isEmpty()) {
            sb.append(" WHEN MATCHED THEN UPDATE SET ").append(updatable.stream()
                    .map(c -> "t." + c + " = s." + c)
                    .collect(Collectors.joining(", ")));
        }
        sb.append(" WHEN NOT MATCHED THEN INSERT (").append(aliased)
                .append(") VALUES (").append(columns.stream()
                        .map(c -> "s." + c)
                        .collect(Collectors.joining(", ")))
                .append(");");
        return sb.toString();
    }

    private static String insertValues(String targetName, List<String> columns) {
        return "INSERT INTO " + targetName
                + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")";
    }

    private static List<String> nonKeyColumns(List<String> columns, List<String> keyColumns) {
        return columns.stream()
                .filter(c -> !containsIgnoreCase(keyColumns, c))
                .collect(Collectors.toList());
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
