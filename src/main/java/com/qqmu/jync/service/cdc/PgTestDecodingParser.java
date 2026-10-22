package com.qqmu.jync.service.cdc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for the {@code test_decoding} text output emitted by
 * {@code pg_logical_slot_get_changes(..., 'include-xids', '1')} /
 * {@code pg_logical_slot_peek_changes}.
 *
 * <p>Typical stream (a committed transaction on {@code public.orders}; captured verbatim
 * from a postgres:16 slot):
 * <pre>
 *   BEGIN 12345
 *   table public.og_nulls: INSERT: id[bigint]:1 note[text]:null
 *   table public.og_full: UPDATE: old-key: id[bigint]:1 name[text]:'a' new-tuple: id[bigint]:1 name[text]:'b'
 *   table public.og_pk: UPDATE: old-key: id[bigint]:1 new-tuple: id[bigint]:2 name[text]:'a'
 *   table public.orders: UPDATE: id[integer]:7 status[text]:'paid'
 *   table public.orders: DELETE: id[integer]:1
 *   COMMIT 12345
 * </pre>
 *
 * <p>Format quirks this parser tolerates:
 * <ul>
 *   <li>Values are written as {@code name[jdbctype-ish]:literal}; strings are single-quoted
 *       with {@code ''} escaping; SQL NULL is rendered as bare lower-case {@code null}
 *       (test_decoding.c: {@code appendStringInfoString(s, "null")}) — matched
 *       case-insensitively for older/odd builds;</li>
 *   <li>UPDATE events print {@code old-key:} + the old image + {@code new-tuple:} + the new
 *       image whenever the old tuple is logged (REPLICA IDENTITY FULL, or a key change);
 *       plain updates print only the new image;</li>
 *   <li>A TOASTed value that was not modified prints as {@code unchanged-toast-datum} —
 *       the real value is not in the stream and must never be written to the target;</li>
 *   <li>Column names are preserved case exactly as PG stored them — a quoted identifier
 *       such as {@code "OrderID"} keeps mixed case here, so the parser must NOT lower-case
 *       them; the caller's {@link CdcTableBinding} uses the exact column name.</li>
 * </ul>
 *
 * <p>Everything we cannot decode (unsupported type, malformed line) surfaces as a skipped
 * row with a reason — never a guess, mirroring {@link SqlRedoParser}'s contract.
 */
public final class PgTestDecodingParser {

    /**
     * The placeholder test_decoding prints for a TOASTed value it cannot reproduce
     * (unchanged by the UPDATE, or an insert that reuses another tuple's on-disk value).
     */
    private static final String UNCHANGED_TOAST = "unchanged-toast-datum";

    /**
     * Parsed in place of the sentinel so it is distinguishable from a quoted text column
     * whose real value happens to be the same literal.
     */
    private static final Object UNCHANGED_TOAST_MARKER = new Object();

    /** "new-tuple:" starts the new image of an UPDATE that logged the old tuple. */
    private static final String NEW_TUPLE_MARKER = "new-tuple:";

    /** "old-key:" starts the old image — present exactly when NEW_TUPLE_MARKER is. */
    private static final String OLD_KEY_MARKER = "old-key:";

    /** The kind of event one parsed row represents. */
    public enum Op { INSERT, UPDATE, DELETE }

    /** One decoded row event, ready for {@link CdcEventApplier}. */
    public static final class ParsedChange {
        public final Op op;
        /** Lower-case "{@code schema.table}" — matches the binding key. */
        public final String qualifiedTable;
        /** Column name (case-preserved) → typed Java value. */
        public final Map<String, Object> row;
        /** Null when the line was cleanly decoded; a human reason when we skipped it. */
        public final String unsupportedReason;

        private ParsedChange(Op op, String qualifiedTable, Map<String, Object> row,
                             String unsupportedReason) {
            this.op = op;
            this.qualifiedTable = qualifiedTable;
            this.row = row;
            this.unsupportedReason = unsupportedReason;
        }

        static ParsedChange ok(Op op, String qt, Map<String, Object> row) {
            return new ParsedChange(op, qt, row, null);
        }

        static ParsedChange skip(String reason) {
            return new ParsedChange(null, null, null, reason);
        }

        public boolean isSkipped() { return unsupportedReason != null; }
    }

    /**
     * One decoded line from the slot. Rows with {@code txStart} / {@code txEnd} are
     * transaction boundaries — the consumer flushes its buffer on {@code txEnd} and starts
     * a fresh batch.
     */
    public static final class SlotLine {
        public final boolean txStart;
        public final boolean txEnd;
        public final Long xid;
        public final ParsedChange change; // null when txStart/txEnd

        private SlotLine(boolean start, boolean end, Long xid, ParsedChange change) {
            this.txStart = start;
            this.txEnd = end;
            this.xid = xid;
            this.change = change;
        }

        static SlotLine start(Long xid) { return new SlotLine(true, false, xid, null); }
        static SlotLine commit(Long xid) { return new SlotLine(false, true, xid, null); }
        static SlotLine row(ParsedChange change) { return new SlotLine(false, false, null, change); }
    }

    /**
     * Parse one raw {@code data} string from the slot. Never throws — a malformed line
     * returns a {@link SlotLine} carrying a {@link ParsedChange#skip(String)} so the
     * consumer counts it as an unsupported row and moves on.
     */
    public SlotLine parse(String line) {
        if (line == null || line.isBlank()) {
            return SlotLine.row(ParsedChange.skip("blank line"));
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("BEGIN ")) {
            return SlotLine.start(parseXid(trimmed.substring(6)));
        }
        if (trimmed.startsWith("COMMIT ")) {
            return SlotLine.commit(parseXid(trimmed.substring(7)));
        }
        if (!trimmed.startsWith("table ")) {
            return SlotLine.row(ParsedChange.skip("not a row event: " + trimmed));
        }
        // Format: "table <schema>.<table>: <OP>: <payload>"
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx < 0) {
            return SlotLine.row(ParsedChange.skip("missing colon after table ref: " + trimmed));
        }
        String qualified = trimmed.substring(6, colonIdx).trim();
        int opIdx = trimmed.indexOf(':', colonIdx + 1);
        if (opIdx < 0) {
            return SlotLine.row(ParsedChange.skip("missing colon after op: " + trimmed));
        }
        String opToken = trimmed.substring(colonIdx + 1, opIdx).trim().toUpperCase(Locale.ROOT);
        String payload = trimmed.substring(opIdx + 1).trim();
        Op op;
        switch (opToken) {
            case "INSERT": op = Op.INSERT; break;
            case "UPDATE": op = Op.UPDATE; break;
            case "DELETE": op = Op.DELETE; break;
            default: return SlotLine.row(ParsedChange.skip("unknown op: " + opToken));
        }
        // For UPDATE, skip the "old key-..." segment; we only need the NEW image (the applier
        // locates rows by PK from the binding, so the old-image values are irrelevant for the
        // upsert semantics. For DELETE, every column pair is the key image — parse as-is.
        Map<String, Object> row = parseColumns(payload, op == Op.UPDATE);
        if (row == null) {
            return SlotLine.row(ParsedChange.skip("malformed column payload: " + payload));
        }
        if (row.containsValue(UNCHANGED_TOAST_MARKER)) {
            if (op == Op.DELETE) {
                // Under REPLICA IDENTITY FULL a DELETE carries the whole old row, but the
                // applier binds only the key columns — drop what cannot be reproduced.
                row.values().removeIf(value -> value == UNCHANGED_TOAST_MARKER);
            } else {
                // The real value is not in the stream. Writing the literal would silently
                // corrupt the target, so skip the row loudly (counted like every other
                // undecodable line) instead of guessing.
                return SlotLine.row(ParsedChange.skip("unchanged-toast-datum: test_decoding"
                        + " cannot reproduce a TOASTed value on " + op + " for " + qualified));
            }
        }
        return SlotLine.row(ParsedChange.ok(op, qualified.toLowerCase(Locale.ROOT), row));
    }

    private static Long parseXid(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse "col[type]:val col2[type]:'quoted val with '' inside' ...". When
     * {@code skipOldImage} is true, the leading "old-key:" fragment (only present on an
     * UPDATE that logged the old tuple) is skipped up to (and including) the
     * "new-tuple:" marker.
     */
    static Map<String, Object> parseColumns(String payload, boolean skipOldImage) {
        String body = payload;
        if (skipOldImage) {
            int newIdx = findNewImageStart(body);
            if (newIdx >= 0) {
                body = body.substring(newIdx);
            } else if (body.startsWith(OLD_KEY_MARKER)) {
                // The old image is present but the new image never followed: parsing the
                // old image as if it were the new one would apply stale values. Refuse.
                return null;
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        int i = 0;
        int n = body.length();
        while (i < n) {
            // skip whitespace
            while (i < n && Character.isWhitespace(body.charAt(i))) i++;
            if (i >= n) break;
            // read column name up to '['
            int nameEnd = body.indexOf('[', i);
            if (nameEnd < 0) return null;
            String name = body.substring(i, nameEnd).trim();
            // read type up to ']'
            int typeEnd = body.indexOf(']', nameEnd + 1);
            if (typeEnd < 0) return null;
            String type = body.substring(nameEnd + 1, typeEnd);
            i = typeEnd + 1;
            // skip whitespace before ':'
            while (i < n && Character.isWhitespace(body.charAt(i))) i++;
            if (i >= n || body.charAt(i) != ':') return null;
            i++; // consume ':'
            // read value: either null (bare word up to whitespace), or a quoted string, or
            // an unquoted token up to whitespace.
            Object value;
            // test_decoding prints SQL NULL as lower-case "null"; old builds/fixtures may
            // carry "NULL" — match case-insensitively, but never a longer word such as
            // "nullable" (hence the trailing-boundary check).
            if (i + 4 <= n && body.regionMatches(true, i, "null", 0, 4)
                    && (i + 4 == n || Character.isWhitespace(body.charAt(i + 4)))) {
                value = null;
                i += 4;
            } else if (i < n && body.charAt(i) == '\'') {
                StringBuilder sb = new StringBuilder();
                i++; // consume opening quote
                while (i < n) {
                    char c = body.charAt(i);
                    if (c == '\'') {
                        if (i + 1 < n && body.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        i++; // consume closing quote
                        break;
                    }
                    sb.append(c);
                    i++;
                }
                value = sb.toString();
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(body.charAt(i))) i++;
                String token = body.substring(start, i);
                // Unquoted by construction: a text column is always printed quoted, so this
                // exact token can only be test_decoding's TOAST placeholder, never data.
                value = UNCHANGED_TOAST.equals(token) ? UNCHANGED_TOAST_MARKER : token;
            }
            // Coerce unquoted scalar values by their declared jdbc type. Quoted strings are
            // left as-is (they came from a text/varchar column). The target JDBC driver
            // otherwise rejects String → bigint/numeric assignments on PG ("column is of
            // type bigint but expression is of type character varying").
            if (value instanceof String s) {
                value = coerceByType(s, type);
            }
            row.put(name, value);
        }
        return row;
    }

    /**
     * P2-2: locate the start of an UPDATE's new image. test_decoding prints
     * {@code old-key: <old image> new-tuple: <new image>}
     * (test_decoding.c, {@code pg_decode_change}), so the separator is the literal word
     * {@code new-tuple:} standing at a whitespace boundary. Scanning quote-aware keeps a
     * quoted old-image value ('see new-tuple: x') from matching; the boundary check keeps
     * a quoted identifier ({@code "new-tuple"}) or a type tag from matching too.
     *
     * @return index just past the marker, or -1 when absent
     */
    static int findNewImageStart(String body) {
        boolean inQuote = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inQuote) {
                if (c == '\'') {
                    if (i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                        i++; // '' escape — still inside the string
                    } else {
                        inQuote = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inQuote = true;
            } else if (isMarkerAt(body, i, NEW_TUPLE_MARKER)) {
                return i + NEW_TUPLE_MARKER.length();
            }
        }
        return -1;
    }

    /** {@code marker} starts at {@code i}, preceded by whitespace (or the payload start)
     * and followed by whitespace or the end — i.e. a standalone token. */
    private static boolean isMarkerAt(String body, int i, String marker) {
        if (!body.startsWith(marker, i)) {
            return false;
        }
        if (i > 0 && !Character.isWhitespace(body.charAt(i - 1))) {
            return false;
        }
        int end = i + marker.length();
        return end == body.length() || Character.isWhitespace(body.charAt(end));
    }

    /**
     * Convert an unquoted scalar literal to the Java type matching the JDBC type tag
     * emitted by test_decoding (e.g. {@code int4}, {@code int8}, {@code numeric}, {@code float8}).
     * Strings that fail to parse fall back to their raw text — never throw, because a
     * malformed value must surface as a skipped row one level up, not crash the consumer.
     */
    private static Object coerceByType(String raw, String type) {
        if (type == null || raw.isEmpty()) {
            return raw;
        }
        String t = type.toLowerCase(Locale.ROOT);
        try {
            switch (t) {
                case "int2":
                case "smallint":
                case "int4":
                case "integer":
                case "oid":
                    return Integer.valueOf(raw);
                case "int8":
                case "bigint":
                    return Long.valueOf(raw);
                case "float4":
                case "real":
                case "float8":
                case "double precision":
                    return Double.valueOf(raw);
                case "numeric":
                case "decimal":
                    // P1-4: numeric keeps up to 38 digits — a Double would silently
                    // round it to ~16 and the target row no longer matches the source.
                    return new java.math.BigDecimal(raw);
                case "money":
                    return parseMoney(raw);
                case "bool":
                case "boolean":
                    // test_decoding prints bool as 't'/'f' or 'true'/'false' — P2-1:
                    // case-insensitive; the old equals("t") silently turned 'T' into false.
                    return raw.equalsIgnoreCase("t") || raw.equalsIgnoreCase("true");
                case "bytea":
                    // P2-1: default bytea_output=hex prints \x48656c6c6f — decode to a
                    // real byte[] so target drivers can bind it; the raw "\x..." string
                    // fails against bytea/blob columns on every target.
                    return parseBytea(raw);
                case "timestamp":
                case "timestamp without time zone":
                case "abstime":
                    // Format: 'YYYY-MM-DD HH:MM:SS.fffffffff' (microsecond precision).
                    // The PG JDBC driver does not coerce String → timestamp on setObject,
                    // so we hand it a real java.sql.Timestamp.
                    return java.sql.Timestamp.valueOf(raw);
                case "timestamptz":
                case "timestamp with time zone":
                    return parseTimestamptz(raw);
                case "date":
                    return java.sql.Date.valueOf(raw);
                case "time":
                case "time without time zone":
                    return java.sql.Time.valueOf(raw);
                case "timetz":
                case "time with time zone":
                    return parseTimetz(raw);
                default:
                    // text, varchar, uuid, json, jsonb, unknown — keep as String.
                    return raw;
            }
        } catch (Exception e) {
            // NumberFormatException / IllegalArgumentException on timestamp parse — fall back
            // to the raw string rather than crashing the consumer.
            return raw;
        }
    }

    // —— P1-4 coercion helpers ——

    /**
     * P2-1: bytea hex output ({@code \x48656c6c6f}, emitted by default
     * {@code bytea_output=hex}) decodes to byte[]. Anything else — escape-format output,
     * odd digit count, non-hex characters — returns the raw string (the old behavior):
     * guessing at binary content is worse than an honest fallback.
     */
    private static Object parseBytea(String raw) {
        String body = raw.trim();
        if (!body.startsWith("\\x") && !body.startsWith("\\X")) {
            return raw;
        }
        String hex = body.substring(2);
        if (hex.length() % 2 != 0) {
            return raw;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return raw;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * money is printed with the locale's decorations: {@code $9.99}, {@code -$1,234.56},
     * or {@code ($0.50)} for negatives under some lc_monetary settings. Strip everything
     * but digits/minus/dot, honor parentheses, keep BigDecimal precision. Unparseable
     * text propagates to {@link #coerceByType}'s catch → raw fallback.
     */
    private static Object parseMoney(String raw) {
        String body = raw.trim();
        boolean negative = body.startsWith("(") && body.endsWith(")");
        if (negative) {
            body = body.substring(1, body.length() - 1);
        }
        StringBuilder digits = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                digits.append(c);
            } else if (c == '-') {
                negative = true;
            }
        }
        java.math.BigDecimal value = new java.math.BigDecimal(digits.toString());
        return negative ? value.negate() : value;
    }

    /**
     * test_decoding emits timestamptz with a trailing UTC offset
     * ({@code 2026-10-13 10:00:00.123456+08}, {@code +05:30}, {@code +0530}) which
     * {@link java.sql.Timestamp#valueOf(String)} rejects — the old code fell back to the
     * raw string and the target bind failed. Parse instant-correct (same semantics as the
     * baseline path's rs.getTimestamp); when no offset is present, fall back to the
     * wall-time parse.
     */
    private static Object parseTimestamptz(String raw) {
        String body = raw.trim();
        java.util.regex.Matcher m = TZ_OFFSET_SUFFIX.matcher(body);
        if (m.find()) {
            try {
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(
                        body.substring(0, m.start()).trim().replace(' ', 'T'));
                java.time.ZoneOffset offset = java.time.ZoneOffset.of(m.group());
                return java.sql.Timestamp.from(ldt.toInstant(offset));
            } catch (RuntimeException e) {
                // malformed offset or body — fall through to the wall-time parse
            }
        }
        return java.sql.Timestamp.valueOf(body);
    }

    /** timetz: drop the UTC offset (a bare wall time is all Time can carry). */
    private static Object parseTimetz(String raw) {
        String body = raw.trim();
        java.util.regex.Matcher m = TZ_OFFSET_SUFFIX.matcher(body);
        if (m.find()) {
            body = body.substring(0, m.start()).trim();
        }
        return java.sql.Time.valueOf(body);
    }

    /** Trailing UTC offset: {@code +HH}, {@code +HHMM} or {@code +HH:MM}. */
    private static final java.util.regex.Pattern TZ_OFFSET_SUFFIX =
            java.util.regex.Pattern.compile("[+-]\\d{2}(:?\\d{2})?$");

    /** Helper for tests: split a multi-line slot batch into a list of lines. */
    static List<String> splitBatch(String batch) {
        List<String> out = new ArrayList<>();
        if (batch == null) return out;
        for (String line : batch.split("\n")) {
            if (!line.isBlank()) out.add(line);
        }
        return out;
    }
}
