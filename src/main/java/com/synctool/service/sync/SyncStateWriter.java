package com.synctool.service.sync;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.synctool.model.ChangeLog;
import com.synctool.model.ChangeType;
import com.synctool.model.ObjectType;
import com.synctool.model.SyncProgress;
import com.synctool.repository.ChangeLogRepository;
import com.synctool.repository.SyncProgressRepository;
import com.synctool.service.metadata.MetadataSnapshotService;
import com.synctool.service.monitor.CursorStrategy;

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

    public SyncStateWriter(SyncProgressRepository progressRepository,
                           ChangeLogRepository changeLogRepository,
                           MetadataSnapshotService snapshotService) {
        this.progressRepository = progressRepository;
        this.changeLogRepository = changeLogRepository;
        this.snapshotService = snapshotService;
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
        if (result.getNewCursorValue() != null) {
            progress.setLastSyncValue(result.getNewCursorValue());
        }
        progress.setCursorColumn(strategy.getColumn());
        progress.setCursorStrategy(strategy.getKind().name());
        progress.setInitialLoadDone(true);
        progress.setLastSyncTime(Instant.now());
        // Counts rows the target actually changed, not rows scanned. A full-compare table is
        // re-read in its entirety every cycle, so accumulating the scan size would inflate this
        // total by the table's row count every few seconds and tell the user nothing.
        progress.setRowsSyncedTotal((progress.getRowsSyncedTotal() == null ? 0L
                : progress.getRowsSyncedTotal()) + result.getRowsChanged());
        progressRepository.save(progress);
    }

    /**
     * Clears a table's cursor so the next cycle re-reads it from the beginning.
     *
     * <p>{@code initialLoadDone} is deliberately left set. A null cursor is already enough to
     * make {@link DataSyncService#syncTable} take the full-load path, and clearing the flag as
     * well would additionally re-arm {@code truncateBeforeInitialLoad} — emptying the target
     * before refilling it, which for a repair would throw away exactly the rows the reload is
     * meant to protect.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearCursor(SyncProgress progress) {
        progress.setLastSyncValue(null);
        progressRepository.save(progress);
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
        snapshotService.deleteAllForProject(projectId);
        log.info("Reset sync state for project {}; the next run performs a full reload", projectId);
    }
}
