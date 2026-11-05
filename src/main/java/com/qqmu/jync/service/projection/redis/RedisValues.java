package com.qqmu.jync.service.projection.redis;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The one place that decides what a JDBC value looks like in Redis.
 *
 * <p>Only types the JDBC drivers actually return are recognised. Anything else yields {@code null}
 * so the caller can skip the whole row: guessing with {@code toString()} would silently write
 * driver object identities into a cache.
 */
final class RedisValues {

    private RedisValues() {
    }

    /** Text form used by HASH fields, keys and JSON strings; {@code null} for an unknown type. */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof CharSequence chars) {
            return chars.toString();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Float || value instanceof Double) {
            return value.toString();
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof LocalDate date) {
            return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (value instanceof LocalTime time) {
            return time.format(DateTimeFormatter.ISO_LOCAL_TIME);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof ZonedDateTime dateTime) {
            return dateTime.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof Instant instant) {
            return DateTimeFormatter.ISO_INSTANT.format(instant);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME);
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return null;
    }

    /**
     * A key component: {@code %}/{@code :} are percent-escaped so the {@code prefix:table:pk}
     * grammar stays unambiguous, and a binary component becomes hex instead of Base64 (hex keeps
     * the key free of {@code +}, {@code /} and {@code =}).
     */
    static String keyComponent(Object value) {
        if (value instanceof byte[] bytes) {
            return hex(bytes);
        }
        String text = text(value);
        if (text == null) {
            throw new IllegalArgumentException(
                    "unsupported primary key value type " + value.getClass().getName());
        }
        return escapeKey(text);
    }

    static String escapeKey(String text) {
        return text.replace("%", "%25").replace(":", "%3A");
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    static int utf8Length(CharSequence text) {
        return text.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Lower-cased copy of a column set, skipping nulls. Column exclusion is matched case-
     * insensitively: a source may name the column {@code PASSWORD} and the exclusion {@code password}.
     */
    static Set<String> lowerCase(Set<String> columns) {
        Set<String> lowered = new LinkedHashSet<>();
        if (columns != null) {
            for (String column : columns) {
                if (column != null) {
                    lowered.add(lower(column));
                }
            }
        }
        return lowered;
    }

    static String lower(String column) {
        return column.toLowerCase(Locale.ROOT);
    }

    /**
     * Appends one JSON value. Returns {@code false} for an unsupported type so the caller skips the
     * row rather than writing a partial document.
     */
    static boolean appendJson(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
            return true;
        }
        if (value instanceof String string) {
            appendQuoted(out, string);
            return true;
        }
        if (value instanceof CharSequence chars) {
            appendQuoted(out, chars.toString());
            return true;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger) {
            out.append(value);
            return true;
        }
        if (value instanceof BigDecimal decimal) {
            appendQuoted(out, decimal.toPlainString());
            return true;
        }
        if (value instanceof Float number) {
            if (Float.isFinite(number)) {
                out.append(number);
            } else {
                out.append("null");
            }
            return true;
        }
        if (value instanceof Double number) {
            if (Double.isFinite(number)) {
                out.append(number);
            } else {
                out.append("null");
            }
            return true;
        }
        if (value instanceof Boolean bool) {
            out.append(bool ? "true" : "false");
            return true;
        }
        String text = text(value);
        if (text == null) {
            return false;
        }
        appendQuoted(out, text);
        return true;
    }

    static void appendQuoted(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
