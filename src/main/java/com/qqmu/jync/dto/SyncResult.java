package com.qqmu.jync.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

import com.qqmu.jync.util.DurationFormat;

/** Aggregate outcome of one sync run, summarized into the task's last result. */
@Getter
@Setter
public class SyncResult {

    private boolean success = true;

    /**
     * Whether the run was aborted by the user's 「停止同步」 request rather than failing.
     *
     * <p>The abort is carried as an error entry (that is how {@code SyncEngine} folds it), so
     * {@code success} is false and everything that only looks at it — the failure ladder, the
     * manual-sync toast — would report a deliberate stop as a fault. This flag is the
     * difference: the runner records such a round as STOPPED, and the HTTP layer answers
     * 「已停止」 instead of 「有失败项」.
     */
    private boolean stopped;

    /**
     * Whether the run gave up its turn because another task's workers held the shared
     * connection pool. Nothing was attempted, so this is neither a success nor a failure:
     * the task row keeps its previous state and the next scheduled fire tries again.
     */
    private boolean deferred;

    private int structureChanges;

    // long, not int: a full baseline of one table can exceed 2^31 rows, and an int counter
    // wrapped negative — the task list then showed "rows +-1294967296" and hasChanges() lied.
    private long rowsInserted;

    private long rowsUpdated;

    private long rowsDeleted;

    private int tablesProcessed;

    private long durationMs;

    private List<String> errors = new ArrayList<>();

    /** Tables that moved no rows this cycle and why; a skip is not a failure. */
    private List<SkippedTable> skippedTables = new ArrayList<>();

    /** A table skipped by the engine with the reason, surfaced in the run summary. */
    public record SkippedTable(String table, String reason) {
    }

    public void addError(String message) {
        this.success = false;
        this.errors.add(message);
    }

    public void addSkipped(String table, String reason) {
        this.skippedTables.add(new SkippedTable(table, reason));
    }

    public long totalRows() {
        return rowsInserted + rowsUpdated + rowsDeleted;
    }

    public boolean hasChanges() {
        return structureChanges > 0 || totalRows() > 0;
    }

    public String summary() {
        if (stopped) {
            // A stop is not a failure: the same sentence the task row gets, so a scheduled
            // fire's log line does not read as "FAILED" for the user's own button press.
            return "Stopped before finishing";
        }
        if (deferred) {
            // Not "No changes": the data was never read, and the next fire will read it.
            return "Deferred: connection pool busy with another task, will retry";
        }
        if (!success) {
            // 只给一句人话:失败了几项、什么原因、哪些表。原文(full DDL)在变更日志里,
            // 这里堆原文会让弹窗和项目列表的 chip 一起变成一屏 SQL,见 ErrorDigest。
            String digest = ErrorDigest.of(errors);
            if (digest == null) {
                return "FAILED: no error detail";
            }
            return "FAILED: " + errors.size() + " error(s) — " + digest;
        }
        if (!hasChanges()) {
            return skippedTables.isEmpty() ? "No changes"
                    : "No changes, skipped " + skippedTables.size() + " table(s)";
        }
        String summary = String.format("DDL=%d, rows +%d ~%d -%d, tables=%d, %s",
                structureChanges, rowsInserted, rowsUpdated, rowsDeleted,
                tablesProcessed, DurationFormat.format(durationMs));
        if (!skippedTables.isEmpty()) {
            summary += ", skipped=" + skippedTables.size();
        }
        return summary;
    }
}
