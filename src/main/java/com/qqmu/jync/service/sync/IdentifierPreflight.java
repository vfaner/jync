package com.qqmu.jync.service.sync;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.SqlDialect;

/**
 * Refuses target identifiers the target cannot store, before the cycle writes anything.
 *
 * <p>Left to the target, a 31-byte name on an 11g Oracle fails mid-load with ORA-00972 — an
 * error that names neither the mapping that produced the name nor the limit it broke. The
 * offending table is reported once, clearly, and dropped from the cycle's metadata so neither
 * structure sync nor the data phase touches it.
 *
 * <p>Limits are per product and measured in the unit the product documents: bytes (UTF-8) for
 * Oracle and PostgreSQL, characters for MySQL and SQL Server ({@link
 * SqlDialect#identifierLimitInBytes()}). A product whose limit is unknown gets no check: a
 * false refusal would block a table that used to sync, which is worse than the late failure
 * this improves.
 */
public final class IdentifierPreflight {

    private IdentifierPreflight() {
    }

    /** One table the target cannot accept, with the message to show the user. */
    public static final class Violation {

        private final String tableName;
        private final String message;

        Violation(String tableName, String message) {
            this.tableName = tableName;
            this.message = message;
        }

        public String getTableName() {
            return tableName;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * @param targetType    the target product, which decides whether version-dependent limits apply
     * @param dialect       supplies the limit for that product
     * @param tables        the tables this cycle would touch
     * @param targetTableName the same source-to-target mapping the cycle uses
     */
    public static List<Violation> check(DatabaseType targetType, SqlDialect dialect,
                                        Collection<TableMeta> tables,
                                        UnaryOperator<String> targetTableName) {
        int limit = limit(targetType, dialect);
        if (limit <= 0 || tables == null || tables.isEmpty()) {
            return List.of();
        }
        boolean bytes = dialect.identifierLimitInBytes();
        String unit = bytes ? " bytes" : " characters";
        List<Violation> violations = new ArrayList<>();
        for (TableMeta table : tables) {
            if (table == null || table.getName() == null) {
                continue;
            }
            String target = targetTableName == null
                    ? table.getName() : targetTableName.apply(table.getName());
            if (target == null || target.isBlank()) {
                target = table.getName();
            }
            int length = measure(target, bytes);
            if (length > limit) {
                violations.add(new Violation(table.getName(), "Table '" + table.getName()
                        + "': target table name '" + target + "' is " + length + unit + "; "
                        + targetType.name() + " allows at most " + limit));
                // One message per table is enough — the name alone already blocks it.
                continue;
            }
            if (table.getColumns() == null) {
                continue;
            }
            for (ColumnMeta column : table.getColumns()) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                int columnLength = measure(column.getName(), bytes);
                if (columnLength > limit) {
                    violations.add(new Violation(table.getName(), "Table '" + table.getName()
                            + "': column '" + column.getName() + "' is " + columnLength + unit
                            + "; " + targetType.name() + " allows at most " + limit));
                }
            }
        }
        return violations;
    }

    /**
     * DM and YashanDB share Oracle's dialect but not its 30-byte legacy cap, and the dialect
     * factory pins them to the 11g branch for unrelated reasons — so the Oracle limit applies
     * to Oracle itself only.
     */
    private static int limit(DatabaseType targetType, SqlDialect dialect) {
        if (targetType == null || dialect == null) {
            return 0;
        }
        if (targetType.getFamily() == DatabaseType.DialectFamily.ORACLE
                && targetType != DatabaseType.ORACLE) {
            return 0;
        }
        return dialect.maxIdentifierBytes();
    }

    /** Length in the product's own unit: UTF-8 bytes, or Unicode code points. */
    private static int measure(String name, boolean bytes) {
        return bytes ? name.getBytes(StandardCharsets.UTF_8).length
                : name.codePointCount(0, name.length());
    }
}
