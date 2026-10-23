package com.qqmu.jync.service.converter;

import java.sql.Types;
import java.util.Locale;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * Canonicalizes product-specific JDBC type codes and type names onto {@link Types}
 * constants, so type mapping and target verification never see an unportable name.
 *
 * <p>Codes collide across products — Oracle's 100 is {@code BINARY_FLOAT}, DB2's is
 * {@code ROWID} — so a code is only interpreted inside the product that defines it.
 * Recognized type names win first: driver versions disagree on the code far more often
 * than on the type name.
 */
public final class VendorTypeMap {

    private VendorTypeMap() {
    }

    /** A canonical JDBC type, optionally fixing the width the generic mapping would need. */
    public record Canonical(int jdbcType, Integer size) {
    }

    /**
     * @param source the source product, or null when unknown
     * @param column the source column as the driver reported it
     * @return the portable type this column should be mapped as
     */
    public static Canonical canonical(DatabaseType source, ColumnMeta column) {
        String name = normalize(column.getTypeName());
        Canonical byName = byTypeName(name);
        if (byName != null) {
            return byName;
        }
        DatabaseType.DialectFamily family =
                source == null ? null : source.getFamily();
        if (family == DatabaseType.DialectFamily.ORACLE) {
            switch (column.getJdbcType()) {
                case 100:
                    // BINARY_FLOAT is IEEE single precision, i.e. JDBC REAL.
                    return new Canonical(Types.REAL, null);
                case 101:
                    return new Canonical(Types.DOUBLE, null);
                case -101:
                case -102:
                    return new Canonical(Types.TIMESTAMP_WITH_TIMEZONE, null);
                case -103:
                case -104:
                    return new Canonical(Types.VARCHAR, 64);
                case 2007:
                case 2009:
                    return new Canonical(Types.CLOB, null);
                default:
                    break;
            }
        } else if (family == DatabaseType.DialectFamily.SQLSERVER) {
            switch (column.getJdbcType()) {
                case -145:
                    return new Canonical(Types.VARCHAR, 36);
                case -146:
                case -148:
                    return new Canonical(Types.DECIMAL, null);
                case -150:
                case -151:
                    return new Canonical(Types.TIMESTAMP, null);
                case -152:
                case -156:
                    return new Canonical(Types.CLOB, null);
                case -155:
                    return new Canonical(Types.TIMESTAMP_WITH_TIMEZONE, null);
                case -157:
                case -158:
                    return new Canonical(Types.BLOB, null);
                default:
                    break;
            }
        } else if (family == DatabaseType.DialectFamily.DB2) {
            switch (column.getJdbcType()) {
                case -100001:
                    return new Canonical(Types.DECIMAL, null);
                case 2009:
                    return new Canonical(Types.CLOB, null);
                case 100:
                    return new Canonical(Types.VARCHAR, 40);
                default:
                    break;
            }
        }
        if (column.getJdbcType() == Types.ROWID) {
            return new Canonical(Types.VARCHAR, 40);
        }
        return new Canonical(column.getJdbcType(), null);
    }

    /** Name-driven canonicalization; null when the name is not a recognized vendor type. */
    private static Canonical byTypeName(String name) {
        if (name == null) {
            return null;
        }
        switch (name) {
            case "BINARY_FLOAT":
                // Single precision, so it must not widen to a DOUBLE target type.
                return new Canonical(Types.REAL, null);
            case "BINARY_DOUBLE":
                return new Canonical(Types.DOUBLE, null);
            case "TIMESTAMPTZ":
            case "DATETIMEOFFSET":
            case "TIMESTAMP WITH TIME ZONE":
                return new Canonical(Types.TIMESTAMP_WITH_TIMEZONE, null);
            case "TIMESTAMPLTZ":
            case "TIMESTAMP WITH LOCAL TIME ZONE":
                return new Canonical(Types.TIMESTAMP, null);
            case "INTERVALYM":
            case "INTERVALDS":
            case "INTERVAL YEAR TO MONTH":
            case "INTERVAL DAY TO SECOND":
                return new Canonical(Types.VARCHAR, 64);
            case "XMLTYPE":
            case "XML":
            case "SQL_VARIANT":
                return new Canonical(Types.CLOB, null);
            case "UNIQUEIDENTIFIER":
                return new Canonical(Types.VARCHAR, 36);
            case "MONEY":
            case "SMALLMONEY":
            case "DECFLOAT":
                return new Canonical(Types.DECIMAL, null);
            case "GEOMETRY":
            case "GEOGRAPHY":
                return new Canonical(Types.BLOB, null);
            case "ROWID":
                return new Canonical(Types.VARCHAR, 40);
            case "ENUM":
            case "SET":
                return new Canonical(Types.VARCHAR, null);
            case "JSON":
            case "JSONB":
                return new Canonical(Types.LONGVARCHAR, null);
            default:
                return null;
        }
    }

    /** True for a column whose text is a JSON document (native JSON targets keep it). */
    public static boolean isJsonTypeName(String typeName) {
        String name = normalize(typeName);
        return "JSON".equals(name) || "JSONB".equals(name);
    }

    /** Types the schema comparison must never treat as orderable/equality-comparable. */
    public static boolean isNonComparableName(String typeName) {
        String name = normalize(typeName);
        return "XML".equals(name) || "XMLTYPE".equals(name) || "SQL_VARIANT".equals(name)
                || "GEOMETRY".equals(name) || "GEOGRAPHY".equals(name);
    }

    /** Broad compatibility family: 1 string, 2 numeric, 3 temporal, 4 binary, 5 boolean. */
    public static int family(DatabaseType product, int jdbcType, String typeName) {
        String name = normalize(typeName);
        if (isTextBorneName(name)) {
            return 1;
        }
        // Name-driven families settle the vendor types whose code means different things in
        // different products (or is not in {@link Types} at all). The source side of the
        // target-shape check has no product to scope by, so the name has to be enough.
        Integer byName = familyByTypeName(name);
        if (byName != null) {
            return byName;
        }
        DatabaseType.DialectFamily dialectFamily = product == null ? null : product.getFamily();
        if (dialectFamily == DatabaseType.DialectFamily.ORACLE) {
            switch (jdbcType) {
                case 100:
                case 101:
                    return 2;
                case -101:
                case -102:
                    return 3;
                case -103:
                case -104:
                case 2007:
                case 2009:
                    return 1;
                default:
                    break;
            }
        } else if (dialectFamily == DatabaseType.DialectFamily.SQLSERVER) {
            switch (jdbcType) {
                case -145:
                case -152:
                case -156:
                    return 1;
                case -146:
                case -148:
                    return 2;
                case -150:
                case -151:
                case -155:
                    return 3;
                case -157:
                case -158:
                    return 4;
                default:
                    break;
            }
        } else if (dialectFamily == DatabaseType.DialectFamily.DB2) {
            switch (jdbcType) {
                case -100001:
                    return 2;
                case 2009:
                    return 1;
                case 100:
                    return 1;
                default:
                    break;
            }
        }
        if (jdbcType == Types.ROWID) {
            return 1;
        }
        return familyByJdbcType(jdbcType);
    }

    /** Families of vendor type names that are unambiguous across products. */
    private static Integer familyByTypeName(String name) {
        if (name == null) {
            return null;
        }
        switch (name) {
            case "BINARY_FLOAT":
            case "BINARY_DOUBLE":
            case "MONEY":
            case "SMALLMONEY":
            case "DECFLOAT":
                return 2;
            case "TIMESTAMPTZ":
            case "TIMESTAMPLTZ":
            case "DATETIMEOFFSET":
            case "TIMESTAMP WITH TIME ZONE":
            case "TIMESTAMP WITH LOCAL TIME ZONE":
                // Oracle's TIMESTAMPLTZ renders as a timestamp; both stay temporal.
                return 3;
            case "GEOMETRY":
            case "GEOGRAPHY":
            case "IMAGE":
                return 4;
            default:
                return null;
        }
    }

    /** Standard {@link Types} families; unknown codes must match exactly (100 + code). */
    public static int familyByJdbcType(int jdbcType) {
        switch (jdbcType) {
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
                return 1;
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL:
                return 2;
            case Types.DATE:
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return 3;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return 4;
            case Types.BOOLEAN:
            case Types.BIT:
                return 5;
            default:
                return 100 + jdbcType;
        }
    }

    private static boolean isTextBorneName(String name) {
        if (name == null) {
            return false;
        }
        switch (name) {
            case "XML":
            case "XMLTYPE":
            case "SQL_VARIANT":
            case "UNIQUEIDENTIFIER":
            case "JSON":
            case "JSONB":
            case "ENUM":
            case "SET":
            case "ROWID":
            case "INTERVALYM":
            case "INTERVALDS":
            case "INTERVAL YEAR TO MONTH":
            case "INTERVAL DAY TO SECOND":
                return true;
            default:
                return false;
        }
    }

    /**
     * Upper-cases a reported type name and strips precision decoration, because drivers
     * decorate the same type differently: ojdbc reports {@code TIMESTAMP(6) WITH TIME ZONE}
     * and {@code INTERVAL DAY(2) TO SECOND(6)} where the type name itself carries neither
     * group. No two types differ only by a parenthesized part, so dropping it lets one
     * spelling of a vendor type match every driver variant.
     */
    private static String normalize(String typeName) {
        if (typeName == null) {
            return null;
        }
        String name = typeName.trim().toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(name.length());
        int depth = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                out.append(c);
            }
        }
        return out.toString().trim().replaceAll("\\s+", " ");
    }
}
