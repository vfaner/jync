package com.qqmu.jync.service.sync;

import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.monitor.CursorStrategy;

import lombok.extern.slf4j.Slf4j;

/**
 * Writes sync bookkeeping — cursors and audit entries — to the tool's own store.
 *
 * <p>A separate bean rather than methods on {@link SyncEngine} because Spring's
 * {@code @Transactional} is proxy-based: a self-invocation inside SyncEngine would bypass the
 * proxy and silently run without a transaction. Crossing a bean boundary is what makes the
 * {@code REQUIRES_NEW} semantics real, which matters because these writes must survive
 * independently of whatever else the cycle is doing.
 */
@Service
@Slf4j
public class SyncStateWriter {

    private final SyncProgressRepository progressRepository;
    private final ChangeLogRepository changeLogRepository;
    private final MetadataSnapshotService snapshotService;
    private final ChunkCheckpointRepository checkpointRepository;

    public SyncStateWriter(SyncProgressRepository progressRepository,
                           ChangeLogRepository changeLogRepository,
                           MetadataSnapshotService snapshotService,
                           ChunkCheckpointRepository checkpointRepository) {
        this.progressRepository = progressRepository;
        this.changeLogRepository = changeLogRepository;
        this.snapshotService = snapshotService;
        this.checkpointRepository = checkpointRepository;
    }

    /**
     * Advances a table's cursor after its rows have been committed to the target.
     *
     * <p>Called only on success. When the data write failed, the cursor is deliberately left
     * where it was so the same window is re-read next cycle; combined with idempotent upserts
     * that yields no lost and no duplicated rows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advanceCursor(SyncProgress progress, DataSyncService.TableSyncResult result,
                              CursorStrategy strategy) {
        boolean changed = false;
        if (result.getNewCursorValue() != null
                && !result.getNewCursorValue().equals(progress.getLastSyncValue())) {
            progress.setLastSyncValue(result.getNewCursorValue());
            changed = true;
        }
        if (!Objects.equals(progress.getCursorColumn(), strategy.getColumn())) {
            progress.setCursorColumn(strategy.getColumn());
            changed = true;
        }
        if (!Objects.equals(progress.getCursorStrategy(), strategy.getKind().name())) {
            progress.setCursorStrategy(strategy.getKind().name());
            changed = true;
        }
        // Only full-load cycles decide this; incremental cycles leave the recorded value alone.
        if (result.getResumableLoad() != null
                && !result.getResumableLoad().equals(progress.getResumableLoad())) {
            progress.setResumableLoad(result.getResumableLoad());
            changed = true;
        }
        if (!Boolean.TRUE.equals(progress.getInitialLoadDone())) {
            progress.setInitialLoadDone(true);
            changed = true;
        }
        // This method runs on every successful cycle, idle ones included — carrying the cursor
        // forward is exactly what stops an idle table re-reading the same window, so the
        // timestamp cannot ride along with it. Same rule as the project's 上次同步: it marks the
        // last cycle that delivered a change (deletes included; getRowsChanged() omits them).
        if (result.getRowsChanged() > 0 || result.getRowsDeleted() > 0) {
            progress.setLastSyncTime(Instant.now());
            changed = true;
        }
        // Counts rows the target actually changed, not rows scanned. A full-compare table is
        // re-read in its entirety every cycle, so accumulating the scan size would inflate this
        // total by the table's row count every few seconds and tell the user nothing.
        if (result.getRowsChanged() > 0) {
            progress.setRowsSyncedTotal((progress.getRowsSyncedTotal() == null ? 0L
                    : progress.getRowsSyncedTotal()) + result.getRowsChanged());
            changed = true;
        }
        // An idle cycle rewrites an identical row otherwise: one UPDATE per table per poll
        // interval, forever, against what is usually the customer's shared production server.
        if (changed) {
            progressRepository.save(progress);
        }
    }

    /**
     * Clears a table's cursor so the next cycle re-reads it from the beginning.
     *
     * <p>{@code initialLoadDone} is deliberately left set. A null cursor is already enough to
     * make {@link DataSyncService#syncTable} take the full-load path, and clearing the flag as
     * well would additionally re-arm {@code truncateBeforeInitialLoad} — emptying the target
     * before refilling it, which for a repair would throw away exactly the rows the reload is
     * meant to protect.
     *
     * <p>Returns the progress instance to continue with; see the save comment for why the
     * caller's own reference is stale once this method commits.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncProgress clearCursor(SyncProgress progress) {
        progress.setLastSyncValue(null);
        // Return the saved row and require callers to continue with it. save() merges this
        // detached instance into a managed copy whose version is bumped at commit, so a caller
        // that kept its old reference would fail the optimistic lock on its next write — which
        // is exactly what happened when a cycle advanced the cursor after an audit repair.
        return progressRepository.save(progress);
    }

    /**
     * Puts a table's progress back to "never loaded" so the next pass rebuilds it from scratch.
     *
     * <p>Used when Jync itself had to recreate a target table the target had lost. Clearing
     * only the cursor is not enough:
     * <ul>
     *   <li>chunk checkpoints left by an interrupted earlier load are resume points for a
     *       target that no longer exists — the "full" reload would skip every range they mark
     *       as copied, into a table holding none of those rows;</li>
     *   <li>{@code initialLoadDone} gates the one-time load of a table with no incremental
     *       strategy, so leaving it set skips such a table forever, and it also arms the steps
     *       that only run for a first load (deferred indexes, identity reseeding).</li>
     * </ul>
     *
     * <p>Unlike {@link #clearCursor(SyncProgress)} this re-arms {@code truncateBeforeInitialLoad}.
     * That is safe here and only here: the caller invokes it right after building an empty
     * target table, so there are no rows a truncate could destroy.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncProgress forceReload(SyncProgress progress) {
        checkpointRepository.deleteByProjectIdAndTableName(
                progress.getProjectId(), progress.getObjectName());
        progress.setLastSyncValue(null);
        progress.setInitialLoadDone(false);
        return progressRepository.save(progress);
    }

    /**
     * Clears every cursor of a project (v1.5.0 C2 strategy switch): on POLLING → CDC the
     * polling cursors are meaningless to the stream, and on CDC → POLLING each table must
     * re-resolve its cursor from scratch rather than resume a pre-CDC window. Rows keep
     * {@code initialLoadDone}, per the single-row variant above.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearCursor(Long projectId) {
        for (SyncProgress progress : progressRepository.findByProjectId(projectId)) {
            clearCursor(progress);
        }
    }

    /**
     * Clears one table's cursor so the next cycle reloads that table from scratch.
     *
     * <p>Used when the table's explicit cursor column changes: the stored watermark was
     * produced by the old column, and a window built from it against a different column
     * compares two unrelated value domains — rows whose value in the new column sits below
     * the old watermark would never be delivered. Other tables keep their cursors.
     *
     * <p>{@code initialLoadDone} stays set for the same reason as
     * {@link #clearCursor(SyncProgress)}: a null cursor already routes the table through the
     * full-load path, and clearing the flag would additionally re-arm
     * {@code truncateBeforeInitialLoad}. A table with no progress row yet is a no-op.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearCursor(Long projectId, String tableName) {
        progressRepository
                .findByProjectIdAndObjectTypeAndObjectName(projectId, ObjectType.TABLE, tableName)
                // Self-invocation is deliberate: this method's REQUIRES_NEW transaction
                // already holds, and routing through the single-row variant keeps the clear
                // in one place.
                .ifPresent(this::clearCursor);
    }

    /** Loads a table's progress row, creating a fresh one on first sight. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncProgress loadOrCreateProgress(Long projectId, String tableName) {
        return progressRepository
                .findByProjectIdAndObjectTypeAndObjectName(projectId, ObjectType.TABLE, tableName)
                .orElseGet(() -> {
                    SyncProgress p = new SyncProgress();
                    p.setProjectId(projectId);
                    p.setObjectType(ObjectType.TABLE);
                    p.setObjectName(tableName);
                    p.setInitialLoadDone(false);
                    p.setRowsSyncedTotal(0L);
                    return progressRepository.save(p);
                });
    }

    /** Appends one audit entry in its own transaction, so failures stay on the record. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLog(Long projectId, ObjectType objectType, String objectName,
                          ChangeType changeType, String details, boolean success,
                          long durationMs) {
        recordLog(projectId, objectType, objectName, changeType, details, success, durationMs,
                null);
    }

    /**
     * Appends one audit entry carrying a row count.
     *
     * <p>{@code affectedRows} is left null for entries where a row count is meaningless — a DDL
     * change, a failure, a project-level start or finish marker — so the log can render a dash
     * there rather than a misleading zero.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLog(Long projectId, ObjectType objectType, String objectName,
                          ChangeType changeType, String details, boolean success,
                          long durationMs, Integer affectedRows) {
        if (!success && isDuplicateFailure(
                errorKey(projectId, objectType, objectName, changeType, details),
                System.currentTimeMillis())) {
            // The first occurrence carries the information; identical repeats one poll interval
            // apart would file tens of thousands of rows a day and bury everything else.
            log.debug("Suppressed a repeated failure log for {} {}: {}",
                    objectType, objectName, details);
            return;
        }
        ChangeLog entry = ChangeLog.of(projectId, objectType, objectName, changeType, details);
        entry.setSuccess(success);
        entry.setDurationMs(durationMs);
        entry.setAffectedRows(affectedRows);
        // Long driver messages would otherwise blow past the column; keep the head, which is
        // where the useful part lives.
        if (details != null && details.length() > 4000) {
            entry.setDetails(details.substring(0, 4000) + "... (truncated)");
        }
        changeLogRepository.save(entry);
    }

    /**
     * How long an identical failure is kept from being written again. Long enough that a
     * two-second poll cannot flood the log, short enough that a failure still in progress keeps
     * reminding the reader it has not gone away.
     */
    static final long ERROR_DEDUP_WINDOW_MS = 60_000;

    /** Capacity after which expired entries are swept; failures are few, keys must not leak. */
    private static final int ERROR_DEDUP_SWEEP_THRESHOLD = 1024;

    /** When each distinct failure was last written, keyed by {@link #errorKey}. */
    private final java.util.Map<String, Long> lastFailureAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** Identity of a failure for de-duplication: the same message on the same object. */
    static String errorKey(Long projectId, ObjectType objectType, String objectName,
                           ChangeType changeType, String details) {
        return projectId + "\u0001" + objectType + "\u0001" + objectName + "\u0001" + changeType
                + "\u0001" + details;
    }

    /**
     * Records {@code now} for the key and answers whether this occurrence should be skipped.
     * Concurrent calls for the same key cannot both win: the first one to store the new time
     * is the only one told to write.
     */
    boolean isDuplicateFailure(String key, long now) {
        boolean[] duplicate = {false};
        lastFailureAt.compute(key, (k, last) -> {
            if (last != null && now - last < ERROR_DEDUP_WINDOW_MS) {
                duplicate[0] = true;
                return last;
            }
            return now;
        });
        if (lastFailureAt.size() > ERROR_DEDUP_SWEEP_THRESHOLD) {
            lastFailureAt.values().removeIf(last -> now - last >= ERROR_DEDUP_WINDOW_MS);
        }
        return duplicate[0];
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSnapshot(Long projectId, ObjectType type, String name, Object definition) {
        snapshotService.save(projectId, type, name, definition);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteSnapshot(Long projectId, ObjectType type, String name) {
        snapshotService.delete(projectId, type, name);
    }

    /** Clears every cursor and snapshot for a project, forcing a full reload next run. */
    @Transactional
    public void resetProject(Long projectId) {
        progressRepository.deleteByProjectId(projectId);
        // Chunk checkpoints are resume points of the load being reset. Left behind, they let
        // the "full reload" skip the ranges they already mark as copied — into a target the
        // reset may also have truncated, or a source whose rows have since changed.
        checkpointRepository.deleteByProjectId(projectId);
        snapshotService.deleteAllForProject(projectId);
        log.info("Reset sync state for project {}; the next run performs a full reload", projectId);
    }
}
