package com.qqmu.jync.service.cdc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One MySQL {@code ENUM} / {@code SET} column's label list, parsed from
 * {@code information_schema.COLUMNS.COLUMN_TYPE} (e.g. {@code enum('red','green')}).
 *
 * <p>Why this exists: the binlog carries a row cell for an ENUM column as its 1-based
 * ordinal ({@code Integer}) and for a SET column as its bitmask ({@code Long}) — the label
 * list is not in the stream (mysql-binlog-connector-java 0.41.5,
 * {@code AbstractRowsEventDataDeserializer#deserializeEnum/deserializeSet}). Every other
 * read path ({@code rs.getString}, {@link com.qqmu.jync.service.sync.JdbcValueNormalizer})
 * hands back the label text, so without this translation the binlog path would write
 * {@code 2} where the baseline writes {@code 'green'} — silent corruption, not a failure.
 */
public final class CdcEnumDefinition {

    private final boolean set;
    private final List<String> labels;

    private CdcEnumDefinition(boolean set, List<String> labels) {
        this.set = set;
        this.labels = Collections.unmodifiableList(labels);
    }

    /**
     * Parses a column definition. Returns null for anything that is not a well-formed
     * {@code enum(...)} / {@code set(...)} — callers then keep the plain binlog value.
     */
    public static CdcEnumDefinition parse(String columnType) {
        if (columnType == null) {
            return null;
        }
        String text = columnType.trim();
        int open = text.indexOf('(');
        if (open < 0 || !text.endsWith(")")) {
            return null;
        }
        String head = text.substring(0, open).trim();
        boolean set;
        if ("enum".equalsIgnoreCase(head)) {
            set = false;
        } else if ("set".equalsIgnoreCase(head)) {
            set = true;
        } else {
            return null;
        }
        List<String> labels = parseLabels(text.substring(open + 1, text.length() - 1));
        return labels == null ? null : new CdcEnumDefinition(set, labels);
    }

    /** Parses {@code 'a','b''c'} — MySQL doubles a single quote inside a label. */
    private static List<String> parseLabels(String body) {
        List<String> labels = new ArrayList<>();
        int i = 0;
        int n = body.length();
        while (true) {
            while (i < n && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            if (i >= n) {
                return labels.isEmpty() ? null : labels;
            }
            if (body.charAt(i) != '\'') {
                return null;
            }
            i++;
            StringBuilder label = new StringBuilder();
            boolean closed = false;
            while (i < n) {
                char c = body.charAt(i);
                if (c == '\'') {
                    if (i + 1 < n && body.charAt(i + 1) == '\'') {
                        label.append('\'');
                        i += 2;
                        continue;
                    }
                    i++;
                    closed = true;
                    break;
                }
                label.append(c);
                i++;
            }
            if (!closed) {
                return null;
            }
            labels.add(label.toString());
            while (i < n && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            if (i >= n) {
                return labels;
            }
            if (body.charAt(i) != ',') {
                return null;
            }
            i++;
        }
    }

    public boolean isSet() {
        return set;
    }

    public List<String> labels() {
        return labels;
    }

    /**
     * Renders the connector's ordinal (ENUM) or bitmask (SET) as the label text
     * {@code rs.getString} would have produced.
     *
     * @throws IllegalArgumentException when the value falls outside this label list —
     *         the definition is stale against the stream; the caller maps that to the
     *         loud skip-and-count path instead of guessing a label.
     */
    public Object render(Number raw) {
        return set ? renderSet(raw.longValue()) : renderEnum(raw.intValue());
    }

    private String renderEnum(int index) {
        // Ordinal 0 is MySQL's "invalid enum value" (only writable in non-strict mode):
        // getString reads it as the empty string.
        if (index == 0) {
            return "";
        }
        if (index < 1 || index > labels.size()) {
            throw new IllegalArgumentException("ENUM ordinal " + index + " outside "
                    + labels.size() + " label(s)");
        }
        return labels.get(index - 1);
    }

    private String renderSet(long bits) {
        if (bits == 0L) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Long.SIZE; i++) {
            if (((bits >>> i) & 1L) == 0L) {
                continue;
            }
            if (i >= labels.size()) {
                throw new IllegalArgumentException("SET bit " + i + " outside "
                        + labels.size() + " label(s)");
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(labels.get(i));
        }
        return out.toString();
    }
}
