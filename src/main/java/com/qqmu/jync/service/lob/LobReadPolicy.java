package com.qqmu.jync.service.lob;

import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-table decision about how each column value leaves the source result set.
 *
 * <ul>
 *   <li>{@link Mode#INLINE} — read with {@code getString}/{@code getBytes}, as before.</li>
 *   <li>{@link Mode#ONE_SHOT} — stream the value; it may only be bound once, before the
 *       cursor advances (the writer flushes the batch and executes such a row alone).</li>
 *   <li>{@link Mode#SPILL} — stream the value to a temp file so it survives multiple
 *       binds (emulated upsert / CDC replay).</li>
 * </ul>
 *
 * <p>Even in a streaming mode, a value that fits under {@code inlineMaxBytes} comes back
 * as a plain {@code String}/{@code byte[]}: a 1KB TEXT column must not degrade a bulk
 * load into one round trip per row.
 */
public final class LobReadPolicy {

    public static final String FORCE_INLINE_MAX_BYTES_KEY = "sync.lob.force-inline-max-bytes";

    public enum Mode { INLINE, ONE_SHOT, SPILL }

    private static final LobReadPolicy DISABLED = new LobReadPolicy(
            "", Mode.INLINE, 0L, 0L, Set.of(), false, Map.of());

    private final String table;
    private final Mode largeMode;
    private final long inlineMaxBytes;
    private final long forceInlineMaxBytes;
    private final Set<String> forceInlineColumns;
    private final boolean enabled;
    private final Map<String, Long> declaredSizes;

    private LobReadPolicy(String table, Mode largeMode, long inlineMaxBytes,
                          long forceInlineMaxBytes, Set<String> forceInlineColumns,
                          boolean enabled, Map<String, Long> declaredSizes) {
        this.table = table;
        this.largeMode = largeMode;
        this.inlineMaxBytes = inlineMaxBytes;
        this.forceInlineMaxBytes = forceInlineMaxBytes;
        this.forceInlineColumns = forceInlineColumns;
        this.enabled = enabled;
        this.declaredSizes = declaredSizes;
    }

    public static LobReadPolicy disabled() {
        return DISABLED;
    }

    /**
     * @param table               table name, only used in error and log messages
     * @param largeMode           mode for values that exceed the inline threshold
     * @param inlineMaxBytes      values at or below this size are materialized
     * @param forceInlineMaxBytes ceiling for columns the writer must hold in memory
     * @param forceInlineColumns  primary key, cursor and cleansed columns (case-insensitive)
     */
    public static LobReadPolicy forTable(String table, Mode largeMode, long inlineMaxBytes,
                                         long forceInlineMaxBytes,
                                         Collection<String> forceInlineColumns) {
        Set<String> names = new HashSet<>();
        if (forceInlineColumns != null) {
            for (String name : forceInlineColumns) {
                if (name != null) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new LobReadPolicy(table == null ? "" : table, largeMode,
                Math.max(0L, inlineMaxBytes), Math.max(0L, forceInlineMaxBytes),
                Set.copyOf(names), true, Map.of());
    }

    /** Adds the result-set metadata sizes the driver reported, keyed case-insensitively. */
    public LobReadPolicy withDeclaredSizes(Map<String, Long> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return this;
        }
        Map<String, Long> normalized = new HashMap<>();
        for (Map.Entry<String, Long> entry : sizes.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                normalized.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        return new LobReadPolicy(table, largeMode, inlineMaxBytes, forceInlineMaxBytes,
                forceInlineColumns, enabled, Map.copyOf(normalized));
    }

    public long declaredSize(String column) {
        Long size = column == null ? null : declaredSizes.get(column.toLowerCase(Locale.ROOT));
        return size == null ? 0L : size;
    }

    public Mode largeMode() {
        return largeMode;
    }

    public Mode modeFor(String column, int jdbcType) {
        return modeFor(column, jdbcType, declaredSize(column));
    }

    public boolean enabled() {
        return enabled;
    }

    public long inlineMaxBytes() {
        return inlineMaxBytes;
    }

    public boolean forceInline(String column) {
        return column != null && forceInlineColumns.contains(column.toLowerCase(Locale.ROOT));
    }

    /**
     * @param declaredSize declared characters/bytes from result-set metadata, or a
     *                     non-positive value when the driver does not know
     * @throws LobLimitExceededException when a force-inline column exceeds its ceiling
     */
    public Mode modeFor(String column, int jdbcType, long declaredSize) {
        if (!enabled) {
            return Mode.INLINE;
        }
        if (forceInline(column)) {
            if (declaredSize > forceInlineMaxBytes) {
                throw new LobLimitExceededException("Column " + qualified(column)
                        + " is " + declaredSize + " characters/bytes, above "
                        + FORCE_INLINE_MAX_BYTES_KEY + "=" + forceInlineMaxBytes
                        + "; primary-key, cursor and cleansed columns must be read whole."
                        + " Raise that setting or exclude the column from the sync.");
            }
            return Mode.INLINE;
        }
        if (isLargeJdbcType(jdbcType) || declaredSize > inlineMaxBytes) {
            // A VARCHAR larger than the threshold is streamed only when the driver
            // actually reported its size; size 0 means "unknown", and guessing wrong
            // would turn an ordinary short column into a per-row round trip.
            return largeMode;
        }
        return Mode.INLINE;
    }

    private String qualified(String column) {
        return table == null || table.isEmpty() ? String.valueOf(column) : table + "." + column;
    }

    /** JDBC types whose driver-side value is a LOB/interruptible stream by definition. */
    static boolean isLargeJdbcType(int jdbcType) {
        switch (jdbcType) {
            case Types.CLOB:
            case Types.NCLOB:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.BLOB:
            case Types.LONGVARBINARY:
                return true;
            default:
                return false;
        }
    }
}
