package com.qqmu.jync.util;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * Checks that an already-existing target table can actually receive the source's rows.
 *
 * <p>This guards the case "table of the right name but the wrong shape" — a manually pre-created
 * table missing a column, or created with stale types before structure sync ever ran. Without a
 * check, data sync fails row-by-row with cryptic driver errors, or worse silently truncates
 * values on a permissive target. The check is deliberately loose: columns must exist and belong
 * to the same broad type family (strings accept strings, numbers accept numbers), since the
 * alternatives (refusing a safe {@code INT→BIGINT}) would block perfectly good deployments.
 */
public final class TargetTableVerifier {

    private TargetTableVerifier() {
    }

    /**
     * @return null when the target is compatible, otherwise a human-readable reason naming the
     *     first column that makes it unusable
     */
    public static String verify(Connection conn, String schema, String tableName,
                                TableMeta sourceTable) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        Map<String, Integer> targetTypes = new LinkedHashMap<>();
        // Catalog stays null: MySQL uses the current database, others key on schema/owner.
        try (ResultSet rs = metaData.getColumns(null, schema, tableName, "%")) {
            while (rs.next()) {
                targetTypes.put(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT),
                        rs.getInt("DATA_TYPE"));
            }
        }

        for (ColumnMeta sourceColumn : sourceTable.getColumns()) {
            Integer targetType = targetTypes.get(sourceColumn.getName().toUpperCase(Locale.ROOT));
            if (targetType == null) {
                return "target table " + tableName + " is missing column " + sourceColumn.getName()
                        + " (a table with this name exists but its shape does not match the"
                        + " source). Fix the table or let structure sync recreate it.";
            }
            if (family(sourceColumn.getJdbcType()) != family(targetType)) {
                return "target table " + tableName + " column " + sourceColumn.getName()
                        + " has an incompatible type (source JDBC type " + sourceColumn.getJdbcType()
                        + " vs target " + targetType + "). Align the column types.";
            }
        }
        return null;
    }

    /** Broad compatibility family; families may freely receive one another's values. */
    private static int family(int jdbcType) {
        switch (jdbcType) {
            case java.sql.Types.CHAR:
            case java.sql.Types.VARCHAR:
            case java.sql.Types.LONGVARCHAR:
            case java.sql.Types.NCHAR:
            case java.sql.Types.NVARCHAR:
            case java.sql.Types.LONGNVARCHAR:
            case java.sql.Types.CLOB:
            case java.sql.Types.NCLOB:
                return 1; // string
            case java.sql.Types.TINYINT:
            case java.sql.Types.SMALLINT:
            case java.sql.Types.INTEGER:
            case java.sql.Types.BIGINT:
            case java.sql.Types.FLOAT:
            case java.sql.Types.REAL:
            case java.sql.Types.DOUBLE:
            case java.sql.Types.NUMERIC:
            case java.sql.Types.DECIMAL:
                return 2; // numeric
            case java.sql.Types.DATE:
            case java.sql.Types.TIME:
            case java.sql.Types.TIME_WITH_TIMEZONE:
            case java.sql.Types.TIMESTAMP:
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:
                return 3; // temporal
            case java.sql.Types.BINARY:
            case java.sql.Types.VARBINARY:
            case java.sql.Types.LONGVARBINARY:
            case java.sql.Types.BLOB:
                return 4; // binary
            case java.sql.Types.BOOLEAN:
            case java.sql.Types.BIT:
                return 5; // boolean
            default:
                return 100 + jdbcType; // unknown types must match exactly
        }
    }
}
