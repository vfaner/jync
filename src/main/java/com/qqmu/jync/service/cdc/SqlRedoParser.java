package com.qqmu.jync.service.cdc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Parses LogMiner SQL_REDO text into a row event the shared {@link CdcEventApplier}
 * can upsert/delete. Pure function, no IO — every dialect shape has a matrix test.
 *
 * <p>Real shapes recorded on gvenzl/oracle-free:23 (CDB mode, DICT_FROM_ONLINE_CATALOG):
 * <pre>
 * insert into "SRC"."SPIKE_T"("ID","NAME","NOTE","TS") values ('1','Alice','hello 世界',TO_TIMESTAMP('...'));
 * update "SRC"."SPIKE_T" set "NOTE" = 'x' where "ID" = '1' and "NAME" = '...' and ROWID = '...';
 * delete from "SRC"."SPIKE_T" where "ID" = '2' and "NAME" = '...' and ROWID = '...';
 * </pre>
 *
 * <p>Key dialect facts this parser is pinned to:
 * <ul>
 *   <li>keywords are lowercase ({@code insert}/{@code update}/{@code delete});</li>
 *   <li>table name and column list have NO space before the opening paren
 *       ({@code "SRC"."T"("ID",...)});</li>
 *   <li>every value is a quoted string, even NUMBER columns ({@code '1'});</li>
 *   <li>UPDATE's WHERE carries the FULL old image (all columns + ROWID), SET carries
 *       only the changed columns — so the row map for an UPDATE is built from the SET
 *       clause, and the WHERE's PK columns are validated for completeness;</li>
 *   <li>ROWID appears in WHERE and must be ignored (it is not a real column);</li>
 *   <li>unsupported/partial images → {@link ParsedChange#unsupported(String)}, never guess.</li>
 * </ul>
 */
@Component
@Slf4j
public class SqlRedoParser {

    public enum Op { INSERT, UPDATE, DELETE }

    public static final class ParsedChange {
        public final Op op;
        /** Upper-case column name → canonical value (null for SQL NULL). */
        public final Map<String, Object> row;
        public final String unsupportedReason;
        /**
         * The binding columns the redo text carried no value for; empty for every refusal
         * that is not a missing-column one. Out-of-line LOBs are the normal cause, and the
         * consumer reports this class separately from ordinary parse failures.
         */
        public final List<String> missingColumns;

        private ParsedChange(Op op, Map<String, Object> row, String unsupportedReason,
                             List<String> missingColumns) {
            this.op = op;
            this.row = row;
            this.unsupportedReason = unsupportedReason;
            this.missingColumns = missingColumns == null ? List.of() : List.copyOf(missingColumns);
        }

        public static ParsedChange unsupported(String reason) {
            return new ParsedChange(null, null, reason, List.of());
        }

        static ParsedChange missingValues(String reason, List<String> missingColumns) {
            return new ParsedChange(null, null, reason, missingColumns);
        }

        static ParsedChange of(Op op, Map<String, Object> row) {
            return new ParsedChange(op, row, null, List.of());
        }
    }

    public ParsedChange parse(Op op, String sqlRedo, CdcTableBinding binding) {
        if (binding == null || binding.getMeta() == null || binding.pkNames().isEmpty()) {
            return ParsedChange.unsupported("no-pk table");
        }
        if (sqlRedo == null || sqlRedo.trim().isEmpty()) {
            return ParsedChange.unsupported("empty sql_redo");
        }
        try {
            switch (op) {
                case INSERT:
                    return parseInsert(sqlRedo, binding);
                case UPDATE:
                    return parseUpdate(sqlRedo, binding);
                case DELETE:
                    return parseDelete(sqlRedo, binding);
                default:
                    return ParsedChange.unsupported("unknown op " + op);
            }
        } catch (OracleLiteralConverters.UnsupportedLiteralException e) {
            return ParsedChange.unsupported(e.getMessage());
        } catch (RuntimeException e) {
            return ParsedChange.unsupported("parse error: " + e.getMessage());
        }
    }

    // —— INSERT ——

    private ParsedChange parseInsert(String sql, CdcTableBinding binding) {
        // insert into "OWNER"."TABLE"("C1","C2",...) values (v1,v2,...);
        int valuesIdx = indexOfWord(sql, "values");
        if (valuesIdx < 0) {
            return ParsedChange.unsupported("insert: no values keyword");
        }
        String colPart = sql.substring(0, valuesIdx);
        String valPart = sql.substring(valuesIdx + "values".length());

        List<String> columns = parseColumnList(colPart);
        List<String> values = parseValueList(valPart);
        if (columns.size() != values.size()) {
            return ParsedChange.unsupported("insert: column/value count mismatch "
                    + columns.size() + " vs " + values.size());
        }
        Map<String, Integer> types = binding.typeByColumn();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String col = columns.get(i);
            Integer sqlType = types.get(col);
            if (sqlType == null) {
                return ParsedChange.unsupported("insert: column " + col + " not in binding");
            }
            row.put(col, OracleLiteralConverters.convert(values.get(i), sqlType));
        }
        ParsedChange incomplete = refuseIfColumnsAreMissing("insert", binding, row);
        return incomplete != null ? incomplete : ParsedChange.of(Op.INSERT, row);
    }

    // —— UPDATE ——

    private ParsedChange parseUpdate(String sql, CdcTableBinding binding) {
        // update "OWNER"."TABLE" set "C1" = v1, "C2" = v2, ... where "PK" = p1 and ...;
        int setIdx = indexOfWord(sql, "set");
        int whereIdx = indexOfWord(sql, "where");
        if (setIdx < 0 || whereIdx < 0 || whereIdx < setIdx) {
            return ParsedChange.unsupported("update: malformed set/where");
        }
        String setPart = sql.substring(setIdx + "set".length(), whereIdx);
        String wherePart = sql.substring(whereIdx + "where".length());

        Map<String, Integer> types = binding.typeByColumn();

        // Build the new-image row. START from the WHERE clause's old image: when the
        // target is an upsert we must include EVERY column the binding knows about, not
        // just the SET columns — otherwise unchanged columns land as NULL on the target
        // (CdcEventApplier.applyNativeUpserts binds every binding column, and JdbcRowMapper
        // treats a missing key as setNull). SET assignments then override whatever the
        // WHERE carried. ROWID is filtered out because it is not a real column.
        //
        // WHERE-value coercion is best-effort: if the literal looks malformed (e.g.
        // trailing semicolon, function call we don't recognise) fall back to the raw
        // string. The target JDBC driver can usually coerce a String for unchanged
        // columns; throwing would poison the whole UPDATE even when SET is clean.
        Map<String, String> whereCols = parseWhereColumns(wherePart);
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : whereCols.entrySet()) {
            String col = e.getKey();
            if ("ROWID".equals(col)) {
                continue;
            }
            Integer sqlType = types.get(col);
            if (sqlType == null) {
                continue;
            }
            try {
                row.put(col, OracleLiteralConverters.convert(e.getValue(), sqlType));
            } catch (OracleLiteralConverters.UnsupportedLiteralException ule) {
                // P1-8: the raw-string fallback stays (throwing would poison the whole
                // UPDATE even when SET is clean) but must not be silent — an unconverted
                // old-image literal landing on the target is otherwise undiagnosable.
                log.warn("UPDATE old-image literal for column {} could not be converted"
                        + " ({}); passing the raw string through — the target driver may"
                        + " coerce or reject it", col, ule.getMessage());
                row.put(col, e.getValue());
            }
        }
        // SET overrides the old image.
        for (Assignment a : parseAssignments(setPart)) {
            Integer sqlType = types.get(a.column);
            if (sqlType == null) {
                return ParsedChange.unsupported("update: set column " + a.column + " not in binding");
            }
            row.put(a.column, OracleLiteralConverters.convert(a.value, sqlType));
        }

        // Validate WHERE contains all PK columns.
        for (String pk : binding.pkNames()) {
            if (!whereCols.containsKey(pk.toUpperCase(Locale.ROOT))) {
                return ParsedChange.unsupported("update: where missing PK column " + pk);
            }
        }
        ParsedChange incomplete = refuseIfColumnsAreMissing("update", binding, row);
        return incomplete != null ? incomplete : ParsedChange.of(Op.UPDATE, row);
    }

    /**
     * Refuses a change whose redo text does not carry every column of the binding.
     *
     * <p>{@link CdcEventApplier} binds the binding's <em>whole</em> column list on every
     * upsert and {@link com.qqmu.jync.service.sync.JdbcRowMapper} maps a column absent from
     * the row to {@code setNull} — so a column the redo text omits silently overwrites the
     * target's value with NULL.
     *
     * <p>That is not hypothetical: a LOB stored <em>out of line</em> (measured with a
     * 10800-byte CLOB) is excluded from supplemental logging, so LogMiner renders the row
     * update without that column in either the SET clause or the WHERE old image:
     * <pre>
     * update "SRC"."OG_LOB" set "BLOB_IN" = HEXTORAW('deadbeef')
     *   where "ID" = '1' and ROWID = '...';
     * </pre>
     * (the CLOB column is simply absent). The change was then applied with a NULL CLOB and
     * no warning, no counter, task status still RUNNING — silent data loss. Inline LOBs and
     * every other type are unaffected: with {@code SUPPLEMENTAL LOG DATA (ALL) COLUMNS} the
     * unchanged columns of an UPDATE appear in the WHERE old image
     * ({@code set "NAME" = 'changed' where "ID" = '7' and "NAME" = 'seed-7' and "AMOUNT"
     * = '7.5' and ROWID = ...}), which this parser folds in.
     *
     * @return the refusal to return, or null when the row carries every binding column
     */
    private static ParsedChange refuseIfColumnsAreMissing(String op, CdcTableBinding binding,
                                                          Map<String, Object> row) {
        List<String> missing = new ArrayList<>();
        for (String column : binding.columnNames()) {
            if (!row.containsKey(column.toUpperCase(Locale.ROOT))) {
                missing.add(column);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        return ParsedChange.missingValues(op + ": redo text carries no value for column(s) "
                + String.join(", ", missing)
                + " — LogMiner renders only what the redo record holds, and neither out-of-line"
                + " LOB content nor (under MINIMAL supplemental logging) the unchanged values of"
                + " an UPDATE are recorded. Applying this change would NULL those target"
                + " column(s) instead. The whole change is skipped so the target row keeps its"
                + " previous consistent version; add SUPPLEMENTAL LOG DATA (ALL) COLUMNS on the"
                + " source table so updates carry the full old image.", missing);
    }

    // —— DELETE ——

    private ParsedChange parseDelete(String sql, CdcTableBinding binding) {
        // delete from "OWNER"."TABLE" where "PK" = p1 and ...;
        int whereIdx = indexOfWord(sql, "where");
        if (whereIdx < 0) {
            return ParsedChange.unsupported("delete: no where");
        }
        String wherePart = sql.substring(whereIdx + "where".length());
        Map<String, String> whereCols = parseWhereColumns(wherePart);

        Map<String, Integer> types = binding.typeByColumn();
        Map<String, Object> row = new LinkedHashMap<>();
        for (String pk : binding.pkNames()) {
            String upper = pk.toUpperCase(Locale.ROOT);
            String value = whereCols.get(upper);
            if (value == null) {
                return ParsedChange.unsupported("delete: where missing PK column " + pk);
            }
            Integer sqlType = types.get(upper);
            if (sqlType == null) {
                return ParsedChange.unsupported("delete: PK column " + pk + " not in binding");
            }
            row.put(upper, OracleLiteralConverters.convert(value, sqlType));
        }
        return ParsedChange.of(Op.DELETE, row);
    }

    // —— SQL scanning helpers (single-pass, quote-aware) ——

    /** Find {@code word} as a whole token (bounded by non-alphanumeric), case-insensitive. */
    static int indexOfWord(String sql, String word) {
        String lower = sql.toLowerCase(Locale.ROOT);
        String w = word.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i <= lower.length() - w.length()) {
            int j = lower.indexOf(w, i);
            if (j < 0) {
                return -1;
            }
            boolean leftOk = j == 0 || !isWordChar(lower.charAt(j - 1));
            boolean rightOk = j + w.length() >= lower.length()
                    || !isWordChar(lower.charAt(j + w.length()));
            if (leftOk && rightOk) {
                return j;
            }
            i = j + 1;
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Parse a column list like {@code ("ID","NAME","NOTE")} (or the part before VALUES
     * for INSERT). Returns upper-case column names without the surrounding quotes.
     */
    static List<String> parseColumnList(String sql) {
        int open = sql.indexOf('(');
        int close = sql.lastIndexOf(')');
        List<String> out = new ArrayList<>();
        if (open < 0 || close < 0 || close <= open) {
            return out;
        }
        String body = sql.substring(open + 1, close);
        for (String token : splitTopLevel(body, ',')) {
            String t = token.trim();
            if (t.startsWith("\"") && t.endsWith("\"")) {
                out.add(t.substring(1, t.length() - 1).toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * Parse a value list like {@code ('1','Alice',TO_TIMESTAMP('...'))} — returns the
     * raw literal fragments (quoted strings kept quoted, function calls kept whole).
     */
    static List<String> parseValueList(String sql) {
        int open = sql.indexOf('(');
        int close = sql.lastIndexOf(')');
        List<String> out = new ArrayList<>();
        if (open < 0 || close < 0 || close <= open) {
            return out;
        }
        String body = sql.substring(open + 1, close);
        for (String token : splitTopLevel(body, ',')) {
            out.add(token.trim());
        }
        return out;
    }

    /** A {@code "COL" = value} pair from a SET clause. */
    private static final class Assignment {
        final String column;
        final String value;
        Assignment(String column, String value) {
            this.column = column;
            this.value = value;
        }
    }

    /** Parse {@code "C1" = v1, "C2" = v2, ...} into assignments. */
    static List<Assignment> parseAssignments(String setPart) {
        List<Assignment> out = new ArrayList<>();
        for (String token : splitTopLevel(setPart, ',')) {
            int eq = indexOfTopLevel(token, '=');
            if (eq < 0) {
                continue;
            }
            String col = token.substring(0, eq).trim();
            String val = token.substring(eq + 1).trim();
            if (col.startsWith("\"") && col.endsWith("\"")) {
                out.add(new Assignment(col.substring(1, col.length() - 1).toUpperCase(Locale.ROOT), val));
            }
        }
        return out;
    }

    /**
     * Parse a WHERE clause into {@code upperColumnName → raw value literal}. ROWID
     * entries are kept too (the caller decides to ignore them); quoted column names
     * are upper-cased.
     */
    static Map<String, String> parseWhereColumns(String wherePart) {
        Map<String, String> out = new LinkedHashMap<>();
        // Split on " and " (case-insensitive) at top level.
        List<String> conjuncts = splitOnAnd(wherePart);
        for (String c : conjuncts) {
            int eq = indexOfTopLevel(c, '=');
            if (eq < 0) {
                continue;
            }
            String col = c.substring(0, eq).trim();
            String val = c.substring(eq + 1).trim();
            if (col.startsWith("\"") && col.endsWith("\"")) {
                out.put(col.substring(1, col.length() - 1).toUpperCase(Locale.ROOT), val);
            } else if (col.toUpperCase(Locale.ROOT).equals("ROWID")) {
                out.put("ROWID", val);
            }
        }
        return out;
    }

    // —— splitters that respect quotes, double-quotes and parentheses ——

    /**
     * P2-6: Oracle alternative quoting — {@code q'[...]'} / {@code q'{...}'} /
     * {@code q'(...)'} / {@code q'<...>'} (and exotic single-char delimiters like
     * {@code q'!...!'}). The content is VERBATIM: it may hold single quotes, commas,
     * "and" or "=" with no escaping, so a plain-quote state machine "exits" the string
     * at the first interior apostrophe and then parses real content as SQL syntax —
     * splitting SET lists, WHERE conjuncts, or value tuples mid-literal.
     *
     * <p>If a q-quote opens at index {@code i} of {@code s} (q/Q, then a quote, then a
     * delimiter; the q itself must start a token — the preceding char is not a word
     * char, ruling out an identifier that merely ends in q), return the index just past
     * the closing quote; -1 when it is not a q-quote or is unterminated (callers then
     * fall back to plain-quote handling, exactly as before).
     */
    static int qQuoteEnd(String s, int i) {
        if (i + 2 >= s.length()) {
            return -1;
        }
        char c = s.charAt(i);
        if ((c != 'q' && c != 'Q') || s.charAt(i + 1) != '\'') {
            return -1;
        }
        if (i > 0 && isWordChar(s.charAt(i - 1))) {
            return -1;
        }
        char open = s.charAt(i + 2);
        char close = switch (open) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> open;
        };
        for (int j = i + 3; j + 1 < s.length(); j++) {
            if (s.charAt(j) == close && s.charAt(j + 1) == '\'') {
                return j + 2;
            }
        }
        return -1;
    }

    /**
     * Split {@code s} on {@code delimiter} at top level (not inside quotes, double-quotes,
     * or nested parentheses).
     */
    static List<String> splitTopLevel(String s, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) {
                    // Detect '' escape inside a single-quoted string.
                    if (quote == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                        continue;
                    }
                    quote = 0;
                }
                continue;
            }
            if (c == 'q' || c == 'Q') {
                int end = qQuoteEnd(s, i);
                if (end > 0) {
                    cur.append(s, i, end);
                    i = end - 1; // the loop's i++ moves past the closing quote
                    continue;
                }
            }
            if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
                cur.append(c);
                continue;
            }
            if (c == ')') {
                depth--;
                cur.append(c);
                continue;
            }
            if (c == delimiter && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Find {@code ch} at top level (not inside quotes or parentheses). */
    static int indexOfTopLevel(String s, char ch) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if (quote == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i++;
                        continue;
                    }
                    quote = 0;
                }
                continue;
            }
            if (c == 'q' || c == 'Q') {
                int end = qQuoteEnd(s, i);
                if (end > 0) {
                    i = end - 1;
                    continue;
                }
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (c == ch && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Split a WHERE clause on the word "AND" (case-insensitive) at top level.
     *
     * <p>Word-boundary rule (H2 fix): LogMiner on some NLS/driver combos emits
     * {@code "COL"='val'AND "COL2"='val2'} with NO whitespace between the closing quote
     * and "AND". Requiring a literal space before "AND" then runs the conjuncts together,
     * and every column after the first PK is silently dropped — turning
     * {@code WHERE "ID"='1' AND "NAME"='x'} into a single conjunct whose value is
     * {@code "'1' AND \"NAME\"='x'"}. So we accept "AND" as a standalone word token:
     * the char BEFORE it must not be a word char, and the char AFTER must not be a word
     * char either. A closing quote, whitespace, or semicolon all qualify as non-word.
     */
    static List<String> splitOnAnd(String wherePart) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        String lower = wherePart.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < wherePart.length()) {
            char c = wherePart.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) {
                    if (quote == '\'' && i + 1 < wherePart.length() && wherePart.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                        continue;
                    }
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == 'q' || c == 'Q') {
                int end = qQuoteEnd(wherePart, i);
                if (end > 0) {
                    cur.append(wherePart, i, end);
                    i = end;
                    continue;
                }
            }
            if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
                i++;
                continue;
            }
            if (c == '(') {
                depth++;
                cur.append(c);
                i++;
                continue;
            }
            if (c == ')') {
                depth--;
                cur.append(c);
                i++;
                continue;
            }
            // Match "and" as a word token at top level — preceded by a non-word char
            // (start of string, quote, whitespace, semicolon), followed by a non-word char.
            if (depth == 0 && lower.startsWith("and", i)) {
                char before = i > 0 ? wherePart.charAt(i - 1) : ' ';
                // "and" occupies i..i+2 — the first char AFTER the token is at i+3.
                // (Was i+4: an off-by-one that refused to split " and ROWID" — an AND
                // followed by one space and an unquoted word — gluing the whole tail
                // onto the preceding column's old-image value.)
                char after = i + 3 < wherePart.length() ? wherePart.charAt(i + 3) : ' ';
                boolean leftOk = !isWordChar(before);
                boolean rightOk = !isWordChar(after);
                if (leftOk && rightOk) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    i += 3; // skip "and" (the loop's i++ moves past the 'd')
                    continue;
                }
            }
            cur.append(c);
            i++;
        }
        if (cur.toString().trim().length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
