package com.qqmu.jync.service.converter;

import java.sql.Types;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * Maps the vendor type declarations used in routine parameters and function return clauses
 * onto the target dialect, reusing the same {@link AbstractSqlDialect#mapType} machinery that
 * table columns go through.
 *
 * <p>Unlike columns, routine metadata carries no JDBC type code — only a declaration such as
 * {@code VARCHAR2(100)}, {@code int(11) unsigned} or {@code character varying(100)} — so the
 * declaration is parsed and its base name resolved to a portable {@link Types} category here.
 * An unrecognized name is reported as unresolved: callers decide whether a portable fallback
 * (parameters, like columns) or an outright refusal (function return types) is right. Inside
 * one product family the source spelling is kept verbatim.
 */
@Component
public class RoutineTypeMapper {

    /** The target type declaration and whether the source name was recognized. */
    public record Mapping(String typeName, boolean resolved) {
    }

    public Mapping map(String declaration, Integer fallbackSize,
                       DatabaseType sourceType, SqlDialect targetDialect) {
        if (declaration == null || declaration.isBlank()) {
            return new Mapping(targetDialect.family() == DatabaseType.DialectFamily.SQLSERVER
                    ? "NVARCHAR(MAX)" : "TEXT", false);
        }
        String raw = declaration.trim();
        if (sourceType != null && sourceType.getFamily() == targetDialect.family()) {
            // Same dictionary: keep the exact spelling, widths and all.
            return new Mapping(raw, true);
        }

        Parsed parsed = parse(raw);
        Integer resolvedType = resolveBaseName(parsed.baseName());
        if (resolvedType == null) {
            // Unrecognized cross-family type: render the portable fallback mapType chooses and
            // flag it so callers can block where a guess would be silent.
            return new Mapping(targetDialect.mapType(synthesize(Types.OTHER, null, null,
                    parsed.baseName()), sourceType), false);
        }

        Integer jdbcType = resolvedType;
        Integer size = null;
        Integer scale = null;
        switch (jdbcType) {
            case Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR,
                    Types.LONGVARCHAR, Types.BINARY, Types.VARBINARY ->
                    size = parsed.first() != null ? parsed.first() : fallbackSize;
            case Types.NUMERIC -> {
                size = parsed.first();
                scale = parsed.second();
            }
            case Types.FLOAT -> {
                // MySQL FLOAT(p): p <= 24 is single precision, anything wider double.
                if (parsed.first() != null) {
                    jdbcType = parsed.first() <= 24 ? Types.REAL : Types.DOUBLE;
                }
            }
            default -> { }
        }

        if (parsed.unsigned() && sourceType != null
                && sourceType.getFamily() == DatabaseType.DialectFamily.MYSQL) {
            // MySQL-family unsigned integer types outrange their signed spelling; widen one
            // band, mirroring what Connector/J reports for the column codes.
            switch (jdbcType) {
                case Types.TINYINT:
                    jdbcType = Types.SMALLINT;
                    break;
                case Types.SMALLINT:
                    jdbcType = Types.INTEGER;
                    break;
                case Types.INTEGER:
                    jdbcType = Types.BIGINT;
                    break;
                case Types.BIGINT:
                    jdbcType = Types.NUMERIC;
                    size = 20;
                    scale = 0;
                    break;
                default:
                    break;
            }
        }

        ColumnMeta synthetic = synthesize(jdbcType, size, scale,
                parsed.baseName() + (parsed.unsigned() ? " UNSIGNED" : ""));
        return new Mapping(targetDialect.mapType(synthetic, sourceType), true);
    }

    /** Base name, the one or two numbers from the parenthesized part, and unsigned flag. */
    private record Parsed(String baseName, Integer first, Integer second, boolean unsigned) {
    }

    private static Parsed parse(String declaration) {
        String text = declaration.trim();
        boolean unsigned = false;

        int open = text.indexOf('(');
        String base;
        Integer first = null;
        Integer second = null;
        if (open >= 0) {
            int close = matchingClose(text, open);
            if (close > open) {
                Integer[] nums = numbers(text.substring(open + 1, close));
                if (nums != null) {
                    first = nums[0];
                    second = nums[1];
                }
                base = text.substring(0, open) + " " + text.substring(close + 1);
            } else {
                // Unbalanced: treat the '(' as ordinary text rather than dropping any of it.
                base = text;
            }
        } else {
            base = text;
        }
        base = base.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (base.endsWith(" UNSIGNED") || base.contains(" UNSIGNED ")
                || base.endsWith(" ZEROFILL") || base.contains(" ZEROFILL ")) {
            unsigned = true;
            base = base.replace(" UNSIGNED", "").replace(" ZEROFILL", "")
                    .trim().replaceAll("\\s+", " ");
        }
        return new Parsed(base, first, second, unsigned);
    }

    private static int matchingClose(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** One or two comma-separated plain integers; null when the content is anything else. */
    private static Integer[] numbers(String content) {
        String[] parts = content.split(",");
        if (parts.length < 1 || parts.length > 2) {
            return null;
        }
        Integer[] nums = new Integer[2];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (!part.matches("\\d+")) {
                return null;
            }
            nums[i] = Integer.parseInt(part);
        }
        return nums;
    }

    /** Resolves a normalized base name to a portable {@link Types} category; null if unknown. */
    private static Integer resolveBaseName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        switch (normalized(name)) {
            // Character types
            case "CHAR", "CHARACTER":
                return Types.CHAR;
            case "NCHAR", "NATIONAL CHAR", "NATIONAL CHARACTER":
                return Types.NCHAR;
            case "VARCHAR", "VARCHAR2", "CHAR VARYING", "CHARACTER VARYING",
                    "NVARCHAR", "NVARCHAR2", "NATIONAL CHAR VARYING",
                    "NATIONAL CHARACTER VARYING", "STRING":
                return Types.VARCHAR;
            case "LONG", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT",
                    "NTEXT", "CLOB", "NCLOB", "SYSNAME":
                return Types.LONGVARCHAR;
            // Exact numerics
            case "TINYINT":
                return Types.TINYINT;
            case "SMALLINT":
                return Types.SMALLINT;
            case "INTEGER", "INT", "MEDIUMINT":
                return Types.INTEGER;
            case "BIGINT":
                return Types.BIGINT;
            case "NUMBER", "NUMERIC", "DECIMAL", "DEC", "MONEY", "SMALLMONEY":
                return Types.NUMERIC;
            case "FLOAT":
                return Types.FLOAT;
            case "REAL":
                return Types.REAL;
            case "DOUBLE", "DOUBLE PRECISION":
                return Types.DOUBLE;
            case "BOOLEAN", "BOOL":
                return Types.BOOLEAN;
            case "BIT":
                return Types.BIT;
            // Temporals
            case "DATE":
                return Types.DATE;
            case "TIME", "TIME WITHOUT TIME ZONE":
                return Types.TIME;
            case "TIME WITH TIME ZONE":
                return Types.TIME_WITH_TIMEZONE;
            case "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE", "DATETIME",
                    "SMALLDATETIME", "DATETIME2":
                return Types.TIMESTAMP;
            case "YEAR":
                // Connector/J reports YEAR as JDBC DATE; the MySQL-family hook in mapByJdbcType
                // recognizes it by name and maps it to YEAR or SMALLINT.
                return Types.DATE;
            case "TIMESTAMP WITH TIME ZONE", "DATETIMEOFFSET":
                return Types.TIMESTAMP_WITH_TIMEZONE;
            // Binary
            case "BINARY":
                return Types.BINARY;
            case "VARBINARY", "BINARY VARYING", "RAW":
                return Types.VARBINARY;
            case "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "IMAGE",
                    "BYTEA", "LONG RAW":
                return Types.BLOB;
            // Special names: VendorTypeMap's name table carries the exact target treatment.
            case "JSON", "JSONB", "XML", "XMLTYPE", "SQL_VARIANT", "GEOMETRY", "GEOGRAPHY",
                    "UUID", "UNIQUEIDENTIFIER", "ROWID", "UROWID", "ENUM", "SET", "VOID",
                    "TIMESTAMPTZ", "TIMESTAMPLTZ", "BINARY_FLOAT", "BINARY_DOUBLE",
                    "INTERVALYM", "INTERVALDS", "INTERVAL YEAR TO MONTH",
                    "INTERVAL DAY TO SECOND":
                return specialType(name);
            default:
                return null;
        }
    }

    /** Maps the special names onto a portable category consistent with {@link VendorTypeMap}. */
    private static int specialType(String name) {
        switch (name) {
            case "JSON", "JSONB":
                return Types.LONGVARCHAR;
            case "XML", "XMLTYPE", "SQL_VARIANT", "VOID", "ENUM", "SET":
                return Types.LONGVARCHAR;
            case "GEOMETRY", "GEOGRAPHY":
                return Types.BLOB;
            case "UUID", "UNIQUEIDENTIFIER", "ROWID", "UROWID", "INTERVALYM", "INTERVALDS",
                    "INTERVAL YEAR TO MONTH", "INTERVAL DAY TO SECOND":
                return Types.VARCHAR;
            case "TIMESTAMPLTZ":
                // Oracle stores it as a plain timestamp; VendorTypeMap maps the name the same way.
                return Types.TIMESTAMP;
            case "TIMESTAMPTZ":
                return Types.TIMESTAMP_WITH_TIMEZONE;
            case "BINARY_FLOAT":
                return Types.REAL;
            case "BINARY_DOUBLE":
                return Types.DOUBLE;
            default:
                return Types.OTHER;
        }
    }

    private static String normalized(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static ColumnMeta synthesize(int jdbcType, Integer size, Integer scale,
                                         String typeName) {
        ColumnMeta column = new ColumnMeta();
        column.setName("routine-type");
        column.setTypeName(typeName);
        column.setJdbcType(jdbcType);
        column.setSize(size);
        column.setDecimalDigits(scale);
        return column;
    }
}
