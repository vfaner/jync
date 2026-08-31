package com.synctool.service.monitor;

import java.sql.Types;
import java.util.List;

import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.TableMeta;

import lombok.Getter;

/**
 * How incremental changes are detected for one table.
 *
 * <p>The strategy determines what "changes since last time" means, and therefore what
 * guarantees the sync can offer:
 *
 * <ul>
 *   <li>{@link Kind#TIMESTAMP} — a last-modified column. Detects inserts <em>and</em>
 *       updates. This is the only strategy that gives genuine incremental replication.
 *   <li>{@link Kind#IDENTITY} — a monotonically increasing key. Detects inserts only;
 *       updates to existing rows are invisible. Chosen only when no timestamp exists.
 *   <li>{@link Kind#FULL_COMPARE} — re-reads the whole table each cycle and upserts every
 *       row. Correct but expensive, so it is gated on a row-count ceiling.
 *   <li>{@link Kind#NONE} — no safe strategy; the table is loaded once and then skipped,
 *       with the reason reported to the user rather than silently ignored.
 * </ul>
 */
@Getter
public class CursorStrategy {

    public enum Kind {
        TIMESTAMP,
        IDENTITY,
        FULL_COMPARE,
        NONE
    }

    /**
     * Prefix used when storing a full-compare "fingerprint" in the progress table's
     * {@code last_sync_value} column. The real cursor column has nothing to compare against
     * for a full-compare table, so we persist a {@code fc:<rowcount>} token instead. This
     * keeps the "did anything change?" question answerable without adding a dedicated column,
     * and prevents every poll from logging a spurious "initial load" entry.
     */
    public static final String FULL_COMPARE_ROWCOUNT_PREFIX = "fc:";

    private final Kind kind;
    private final String column;
    private final int jdbcType;
    /** Explains the choice, surfaced in the UI so the user can override it. */
    private final String rationale;
    /** True when updates to existing rows can be missed by this strategy. */
    private final boolean missesUpdates;

    private CursorStrategy(Kind kind, String column, int jdbcType, String rationale,
                           boolean missesUpdates) {
        this.kind = kind;
        this.column = column;
        this.jdbcType = jdbcType;
        this.rationale = rationale;
        this.missesUpdates = missesUpdates;
    }

    public static CursorStrategy timestamp(String column, int jdbcType, String rationale) {
        return new CursorStrategy(Kind.TIMESTAMP, column, jdbcType, rationale, false);
    }

    public static CursorStrategy identity(String column, int jdbcType, String rationale) {
        return new CursorStrategy(Kind.IDENTITY, column, jdbcType, rationale, true);
    }

    public static CursorStrategy fullCompare(String rationale) {
        return new CursorStrategy(Kind.FULL_COMPARE, null, Types.NULL, rationale, false);
    }

    public static CursorStrategy none(String rationale) {
        return new CursorStrategy(Kind.NONE, null, Types.NULL, rationale, true);
    }

    public boolean isIncremental() {
        return kind == Kind.TIMESTAMP || kind == Kind.IDENTITY;
    }

    /** Column-name fragments that conventionally mark a last-modified timestamp. */
    static final List<String> TIMESTAMP_HINTS = List.of(
            "UPDATE_TIME", "UPDATED_AT", "UPDATETIME", "UPDATED_TIME",
            "LAST_MODIFIED", "LASTMODIFIED", "LAST_UPDATE", "LAST_UPDATED",
            "MODIFY_TIME", "MODIFIED_AT", "MODIFIED_TIME", "GMT_MODIFIED",
            "ROW_VERSION", "ROWVERSION", "SYS_UPDATE_TIME", "DATA_CHANGE_TIME");

    /** Weaker hints: creation timestamps catch inserts but never updates. */
    static final List<String> CREATE_TIME_HINTS = List.of(
            "CREATE_TIME", "CREATED_AT", "CREATETIME", "CREATED_TIME",
            "GMT_CREATE", "INSERT_TIME", "ADD_TIME");

    /** True when the type can be compared with {@code >} and aggregated with {@code MAX()}. */
    public static boolean isTemporalType(int jdbcType) {
        return jdbcType == Types.TIMESTAMP
                || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE
                || jdbcType == Types.DATE
                || jdbcType == Types.TIME;
    }

    public static boolean isIntegralType(int jdbcType) {
        return jdbcType == Types.INTEGER
                || jdbcType == Types.BIGINT
                || jdbcType == Types.SMALLINT
                || jdbcType == Types.TINYINT
                || jdbcType == Types.NUMERIC
                || jdbcType == Types.DECIMAL;
    }

    /** True when the column is a single-column integral primary key, i.e. usable as a cursor. */
    static boolean isSingleIntegralPk(TableMeta table, ColumnMeta column) {
        return table.getPrimaryKeys().size() == 1
                && table.getPrimaryKeys().get(0).equalsIgnoreCase(column.getName())
                && isIntegralType(column.getJdbcType());
    }

    @Override
    public String toString() {
        return kind + (column != null ? "(" + column + ")" : "");
    }
}
