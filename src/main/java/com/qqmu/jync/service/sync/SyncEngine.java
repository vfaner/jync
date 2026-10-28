package com.qqmu.jync.service.sync;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;
import com.qqmu.jync.util.TargetTableVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates one full sync cycle: detect structural changes, apply them, then move data.
 *
 * <p>Ordering matters. Structure is applied before data, so a newly added column exists before
 * the rows that populate it arrive. Within data sync, each table's cursor is persisted only
 * after that table's rows are committed to the target — see {@link DataSyncService} for why
 * that ordering is what makes concurrent source writes safe.
 */
@Service
@Slf4j
public class SyncEngine {

    private final DataSourceManager dataSourceManager;
    private final ChangeDetector changeDetector;
    private final CursorStrategyResolver cursorResolver;
    private final StructureSyncService structureSync;
    private final DataSyncService dataSync;
    private final SyncStateWriter stateWriter;
    private final SyncProperties properties;

    /**
     * When each table's row counts were last audited, keyed by {@code projectId:tableName}.
     *
     * <p>Held in memory on purpose. Losing it on restart costs one extra audit per table, which
     * is a good moment to run one anyway.
     */
    private final Map<String, Long> lastRowCountAudit = new ConcurrentHashMap<>();

    /**
     * Tables already confirmed column-compatible with their source, keyed
     * {@code projectId:targetSchema:targetTableName} (upper-cased).
     *
     * <p>In-memory on purpose: the check is one round of {@code DatabaseMetaData.getColumns}
     * per table, so it runs once per process lifetime, not once per poll. A table fixed
     * manually after being flagged is picked up after a restart (or by resetting the project,
     * which evicts the key). The verdict is also dropped when that table's data sync fails
     * ({@link #forgetVerifiedShape}): the target may have been altered after the check, and a
     * stale verdict would keep the repair path from ever running again.
     */
    private final Set<String> verifiedTables = ConcurrentHashMap.newKeySet();

    /**
     * Project/table pairs already warned about exceeding the FULL_COMPARE row cap.
     *
     * <p>An explicit FULL_COMPARE choice bypasses the resolver's cap, and the data phase then
     * re-reads the whole table every poll interval. The first such cycle says so at WARN;
     * repeating it every poll would bury the log, and staying silent would leave the source
     * load unexplained. In-memory on purpose: a restart is a fine moment to say it again.
     */
    private final Set<String> warnedOversizedFullCompare = ConcurrentHashMap.newKeySet();

    /**
     * Project/table pairs already told that deletion detection cannot run for them.
     *
     * <p>The comparison reads both key sets in memory, so a table over the row cap (or one
     * with no size estimate) is skipped. Silent skipping means "同步删除" quietly does nothing
     * on exactly the tables people care about; per-poll repetition would bury the log. One
     * WARN and one change-log entry per process, then the note stays in the audit trail.
     */
    private final Set<String> warnedDeletionSkips = ConcurrentHashMap.newKeySet();

    public SyncEngine(DataSourceManager dataSourceManager,
                      ChangeDetector changeDetector,
                      CursorStrategyResolver cursorResolver,
                      StructureSyncService structureSync,
                      DataSyncService dataSync,
                      SyncStateWriter stateWriter,
                      SyncProperties properties) {
        this.dataSourceManager = dataSourceManager;
        this.changeDetector = changeDetector;
        this.cursorResolver = cursorResolver;
        this.structureSync = structureSync;
        this.dataSync = dataSync;
        this.stateWriter = stateWriter;
        this.properties = properties;
    }

    /**
     * Overrides the lease renewer carried on the context for this cycle, then runs it. Mainly
     * used by tests simulating a lost lease; production passes the renewer in via the context.
     */
    public SyncResult runCycle(SyncContext ctx, BooleanSupplier lockRenewer) {
        return runCycle(ctx.toBuilder().lockRenewer(lockRenewer).build());
    }

    /**
     * Runs one complete cycle.
     *
     * <p>The caller must already hold the project's sync lock. The lease is renewed at each
     * structural event and after every committed data batch (via the renewer on the context);
     * a failed renewal aborts the cycle before further writes. Exceptions are folded into the
     * result rather than propagated, so one failing project reports its error instead of
     * taking down the scheduler.
     */
    public SyncResult runCycle(SyncContext ctx) {
        SyncResult result = new SyncResult();

        try (Connection sourceConn = dataSourceManager.getConnection(ctx.getSourceConfig());
             Connection targetConn = dataSourceManager.getConnection(ctx.getTargetConfig())) {

            DatabaseMeta sourceMeta = readSourceMetadata(sourceConn, ctx);

            // Validate the name mapping against the tables that actually exist: the save-time
            // check cannot see an unmapped source table whose name another table maps onto
            // (e.g. A -> B while B exists). Such a pair writes one shared target, so the cycle
            // stops here, before any structure or data change is attempted.
            List<String> mappingConflicts = ctx.getConfig().tableMappingConflicts(
                    sourceMeta.getTables().stream().map(TableMeta::getName).toList());
            if (!mappingConflicts.isEmpty()) {
                for (String conflict : mappingConflicts) {
                    log.error("Project '{}': {}", ctx.projectName(), conflict);
                    result.addError(conflict);
                    stateWriter.recordLog(ctx.projectId(), ObjectType.TABLE, ctx.projectName(),
                            ChangeType.ERROR, conflict, false, 0);
                }
                return result;
            }

            // A target name that cannot exist is refused here, before any DDL or row write:
            // left to the target, the failure arrives mid-load and names neither the mapping
            // that produced the name nor the limit it broke. Offending tables are dropped from
            // this cycle's metadata, so neither the structure phase nor the data phase touches
            // them.
            List<IdentifierPreflight.Violation> identifierViolations = IdentifierPreflight.check(
                    ctx.effectiveTargetType(), ctx.getTargetDialect(),
                    sourceMeta.getTables().stream()
                            .filter(t -> ctx.getConfig().includesTable(t.getName())).toList(),
                    ctx.getConfig()::targetTableName);
            if (!identifierViolations.isEmpty()) {
                Set<String> offendingNames = new HashSet<>();
                for (IdentifierPreflight.Violation violation : identifierViolations) {
                    log.error("Project '{}': {}", ctx.projectName(), violation.getMessage());
                    result.addError(violation.getMessage());
                    stateWriter.recordLog(ctx.projectId(), ObjectType.TABLE,
                            violation.getTableName(), ChangeType.ERROR, violation.getMessage(),
                            false, 0);
                    offendingNames.add(violation.getTableName());
                }
                sourceMeta.setTables(sourceMeta.getTables().stream()
                        .filter(t -> !offendingNames.contains(t.getName()))
                        .collect(java.util.stream.Collectors.toList()));
            }

            // Nothing selected means no table may be touched — including the retry of an
            // index that failed before the selection was narrowed. Without this guard a
            // "sync nothing" project would still issue DDL against its target each cycle.
            boolean noTableSelected = ctx.getConfig().isObjectSelectionExplicit()
                    && ctx.getConfig().getTables().isEmpty();
            if (!noTableSelected && ctx.getConfig().isSyncStructure()
                    && ctx.getConfig().isSyncIndexes()) {
                retryFailedIndexes(targetConn, ctx, result);
            }

            Set<String> blockedTables = Set.of();
            if (ctx.getConfig().isSyncStructure() || ctx.getConfig().isSyncViews()
                    || ctx.getConfig().isSyncProcedures()) {
                blockedTables = applyStructuralChanges(sourceMeta, sourceConn, targetConn, ctx,
                        result);
            }

            warnOversizedFullCompareTables(sourceMeta, ctx);

            if (ctx.getConfig().isSyncData()) {
                syncData(sourceMeta, sourceConn, targetConn, ctx, result, blockedTables);
            }

        } catch (SQLException e) {
            log.error("Sync cycle failed for project '{}': {}", ctx.projectName(), e.getMessage());
            result.addError("Connection or query failure: " + e.getMessage());
            stateWriter.recordLog(ctx.projectId(), ObjectType.PROJECT, ctx.projectName(),
                    ChangeType.ERROR, e.getMessage(), false, 0);
        } catch (LockLostException e) {
            log.error("Project '{}': {}", ctx.projectName(), e.getMessage());
            result.addError(e.getMessage());
            stateWriter.recordLog(ctx.projectId(), ObjectType.PROJECT, ctx.projectName(),
                    ChangeType.ERROR, e.getMessage(), false, 0);
        } catch (StopRequestedException e) {
            // A stop-requested abort (final review FIX 2): the load stopped at a chunk
            // boundary because its thread was interrupted (CDC) or because the polling
            // runner's stop signal was seen at the lease check (the UI's stop button) — a
            // legitimate stop, not a failure. Folded into the result (unsuccessful, so the
            // CDC baseline reruns on the next start) but with NO ERROR change-log row:
            // shutdown/disable must exit quietly. The interrupt flag, when there is one,
            // stays set for the CDC layers above to consult.
            log.info("Sync cycle for project '{}' aborted by a stop request: {}",
                    ctx.projectName(), e.getMessage());
            result.addError(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Sync cycle failed for project '{}'", ctx.projectName(), e);
            result.addError(String.valueOf(e.getMessage()));
            stateWriter.recordLog(ctx.projectId(), ObjectType.PROJECT, ctx.projectName(),
                    ChangeType.ERROR, String.valueOf(e.getMessage()), false, 0);
        }

        // The runner stamps duration over the whole execute() span (bookkeeping included);
        // setting it here too just overwrote the same field with a near-identical value.
        return result;
    }

    /**
     * Whether this cycle's data phase loads with the target's foreign keys enforced.
     *
     * <p>The engine derives load order from each table's outgoing foreign keys, so the
     * dictionary is only worth reading when a child loaded before its parent would actually be
     * rejected: the user can keep the target's constraints on, or the dialect may have no
     * toggle at all (Oracle and its look-alikes, PostgreSQL), which leaves them on regardless
     * of the switch.
     */
    private static boolean dataPhaseNeedsForeignKeys(SyncContext ctx) {
        if (!ctx.getConfig().isSyncData()) {
            return false;
        }
        return !ctx.getConfig().isDisableTargetConstraints()
                || !ctx.getTargetDialect().supportsConstraintToggle();
    }

    private DatabaseMeta readSourceMetadata(Connection sourceConn, SyncContext ctx)
            throws SQLException {
        List<String> tables = selection(ctx.getConfig().getTables(),
                ctx.getConfig().isObjectSelectionExplicit());
        List<String> views = selection(ctx.getConfig().getViews(),
                ctx.getConfig().isObjectSelectionExplicit());
        List<String> procs = selection(ctx.getConfig().getProcedures(),
                ctx.getConfig().isObjectSelectionExplicit());

        // Structure sync off means change detection never runs, so the index and comment
        // dictionaries have no consumer this cycle: skip them rather than fetch and discard
        // them on every poll. The foreign-key dictionary is not in that group — the data phase
        // orders tables parent-first from it, and that order only becomes visible when the
        // target loads with its constraints enforced. Columns, the primary key and the cursor
        // probes are always read — the data phase needs those.
        DatabaseMeta meta;
        if (ctx.getConfig().isSyncStructure()) {
            meta = ctx.getSourceReader().readAll(sourceConn, ctx.getSourceSchema(),
                    tables, views, procs,
                    ctx.getConfig().isSyncViews(), ctx.getConfig().isSyncProcedures());
        } else if (dataPhaseNeedsForeignKeys(ctx)) {
            meta = ctx.getSourceReader().readAllForDataWithForeignKeys(sourceConn,
                    ctx.getSourceSchema(), tables, views, procs,
                    ctx.getConfig().isSyncViews(), ctx.getConfig().isSyncProcedures());
        } else {
            meta = ctx.getSourceReader().readAllForData(sourceConn, ctx.getSourceSchema(),
                    tables, views, procs,
                    ctx.getConfig().isSyncViews(), ctx.getConfig().isSyncProcedures());
        }

        // Row estimates are a catalog round trip per table per cycle, so collect them here
        // only when a consumer reads every table's number. Deletion detection caps its key
        // scan by table size, and an explicit FULL_COMPARE project warns about (and banners)
        // the tables it re-reads each cycle — both need the whole set. Everything else reaches
        // the estimate only through the automatic-strategy fallback, which asks about the one
        // table that has no cursor candidate at all; that table fetches its own estimate in
        // syncOneTable. Collecting for every table there is pure cost: a 2000-table project
        // over a 20 ms link would spend 40 s per cycle on numbers nobody reads.
        if (ctx.getConfig().isSyncData() && rowEstimatesAreReadForEveryTable(ctx.getConfig())) {
            for (TableMeta table : meta.getTables()) {
                try {
                    table.setApproximateRowCount(ctx.getSourceReader()
                            .estimateRowCount(sourceConn, ctx.getSourceSchema(), table.getName()));
                } catch (SQLException e) {
                    log.debug("Row estimate failed for {}: {}", table.getName(), e.getMessage());
                }
            }
        }
        return meta;
    }

    /**
     * Whether this task's data phase reads the row estimate of <em>every</em> table.
     *
     * <p>Deletion detection asks "is this table small enough to scan its whole key set", and
     * an explicit full-comparison task warns and banners from the same numbers — so those two
     * configurations need the set up front. A polling task with delete detection off reads
     * the estimate only at the strategy fallback (see {@link #syncOneTable}).
     */
    private static boolean rowEstimatesAreReadForEveryTable(SyncConfig config) {
        return config.isSyncDeletes()
                || config.getCaptureStrategyOrDefault() == CaptureStrategy.FULL_COMPARE;
    }

    /**
     * {@code null} = read every object the source discovers (legacy empty selection);
     * empty list = read none (the user unchecked everything and saved).
     */
    private static List<String> selection(Set<String> names, boolean explicit) {
        if (!names.isEmpty()) {
            return new ArrayList<>(names);
        }
        return explicit ? List.of() : null;
    }

    /** Retries secondary indexes that failed during table creation in earlier cycles. */
    private void retryFailedIndexes(Connection targetConn, SyncContext ctx, SyncResult result) {
        ctx.requireLease();
        for (String detail : structureSync.retryPendingIndexes(targetConn, ctx)) {
            log.info("Project '{}': {}", ctx.projectName(), detail);
            stateWriter.recordLog(ctx.projectId(), ObjectType.INDEX,
                    detail.contains(" on ") ? detail.substring(detail.indexOf(" on ") + 4)
                            : ctx.projectName(),
                    ChangeType.CREATE, detail, true, 0);
        }
    }

    /**
     * Applies each detected structural change, snapshotting per object as it succeeds.
     *
     * <p>Views and routines snapshot object-by-object, so a run that dies halfway keeps the
     * objects it already applied and re-detects only the remainder.
     *
     * <p>Table-shaped events (TABLE/COLUMN/INDEX) are different: a table's snapshot describes
     * its whole shape, so saving it while another event for the same table failed or was
     * skipped would tell the detector the entire change set landed — and the unapplied part
     * would never be retried. Those snapshot writes are therefore deferred until the batch is
     * done, and withheld for any table that did not fully converge; the withheld diff is what
     * keeps the change detectable for the next cycle.
     *
     * @return the tables whose snapshot updates were withheld. The data phase must not refresh
     *         their snapshots either, or the retry record would be lost after all.
     */
    private Set<String> applyStructuralChanges(DatabaseMeta sourceMeta, Connection sourceConn,
                                               Connection targetConn, SyncContext ctx,
                                               SyncResult result) {
        // Drops are only propagated when the source account can see the whole schema; the
        // reader probes that once, and only if this detection found something missing.
        List<ChangeEvent> events = changeDetector.detect(ctx.projectId(), sourceMeta,
                ctx.getConfig(), () -> ctx.getSourceReader()
                        .schemaListingIsComplete(sourceConn, ctx.getSourceSchema()));
        if (events.isEmpty()) {
            return Set.of();
        }

        // Case-insensitive on purpose: the same table can be named by different events, and
        // the block must hold no matter which spelling arrives first.
        Set<String> blockedTables = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Runnable> deferredTableSnapshots = new LinkedHashMap<>();

        for (ChangeEvent event : events) {
            // A long DDL batch (many events for many new tables) can outlive the lease. Renew
            // before applying each event; a lost lease aborts via LockLostException. Events
            // already applied have their non-table snapshots saved; the table-shaped ones are
            // still deferred and therefore simply re-detect and replay next time.
            ctx.requireLease();
            long eventStart = System.currentTimeMillis();
            StructureSyncService.ApplyOutcome outcome = structureSync.apply(targetConn, event, ctx);

            if (outcome.error != null) {
                result.addError(event + ": " + outcome.error);
                stateWriter.recordLog(ctx.projectId(), structureSync.logType(event.getObjectType()),
                        event.getObjectName(), event.getChangeType(), outcome.error, false,
                        System.currentTimeMillis() - eventStart);
                if (affectsTableShape(event)) {
                    blockedTables.add(event.getObjectName());
                }
                continue;
            }
            if (!outcome.applied) {
                log.debug("Skipped {}: {}", event, outcome.detail);
                if (affectsTableShape(event)) {
                    blockedTables.add(event.getObjectName());
                }
                continue;
            }

            result.setStructureChanges(result.getStructureChanges() + 1);
            stateWriter.recordLog(ctx.projectId(), structureSync.logType(event.getObjectType()),
                    event.getObjectName(), event.getChangeType(), outcome.detail, true,
                    System.currentTimeMillis() - eventStart);
            if (builtTargetTable(event, outcome)) {
                resetProgressOfCreatedTable(ctx, event.getObjectName());
            }

            if (affectsTableShape(event)) {
                if (structureSync.hasPendingIndexes(ctx.projectId(), event.getObjectName())) {
                    // The shape landed but an index did not. The retry queue is in memory, so
                    // the snapshot is the only record that survives a restart; saving it now
                    // would make the missing index undetectable forever.
                    blockedTables.add(event.getObjectName());
                } else {
                    deferredTableSnapshots.putIfAbsent(event.getObjectName(),
                            () -> updateSnapshotFor(ctx.projectId(), event, sourceMeta));
                }
            } else {
                updateSnapshotFor(ctx.projectId(), event, sourceMeta);
            }
        }

        deferredTableSnapshots.forEach((tableName, save) -> {
            if (blockedTables.contains(tableName)) {
                log.debug("Withholding the snapshot update for {}: a structural change failed"
                        + " or was skipped this cycle, so it must stay detectable", tableName);
            } else {
                save.run();
            }
        });
        return blockedTables;
    }

    /**
     * A target table this phase just built holds nothing, so any stored cursor describes rows
     * that do not exist. Reset it here, at creation time, rather than leaving it to the data
     * phase: a cycle that stops between the phases (lease lost, stop requested) or runs
     * structure only still saves the snapshot, so the CREATE is never re-detected — and the
     * next data phase would resume above the old watermark of an empty table.
     */
    private void resetProgressOfCreatedTable(SyncContext ctx, String tableName) {
        stateWriter.forceReload(stateWriter.loadOrCreateProgress(ctx.projectId(), tableName));
    }

    /**
     * Whether an applied event is the CREATE TABLE that built the target table itself.
     *
     * <p>The data phase reads the target listing after this phase ran, so a table created here
     * is indistinguishable from one that was already there — unless the phase says so. Its
     * stored progress belongs to a previous life of that table and must be reset.
     */
    private static boolean builtTargetTable(ChangeEvent event,
                                            StructureSyncService.ApplyOutcome outcome) {
        return event.getObjectType() == ObjectType.TABLE
                && event.getChangeType() == ChangeType.CREATE
                && outcome.detail != null && outcome.detail.startsWith("Created table");
    }

    /** True for events whose outcome changes what the owning table's snapshot should say. */
    private static boolean affectsTableShape(ChangeEvent event) {
        switch (event.getObjectType()) {
            case TABLE:
            case COLUMN:
            case INDEX:
                return true;
            default:
                return false;
        }
    }

    /**
     * Records the new source shape for an applied change.
     *
     * <p>Column and index events refresh the whole table snapshot, since snapshots are stored
     * per table and a partial update would make the next diff incorrect.
     */
    private void updateSnapshotFor(Long projectId, ChangeEvent event, DatabaseMeta sourceMeta) {
        boolean dropped = event.getChangeType() == ChangeType.DROP;
        switch (event.getObjectType()) {
            case TABLE:
                if (dropped) {
                    stateWriter.deleteSnapshot(projectId, ObjectType.TABLE, event.getObjectName());
                } else {
                    stateWriter.saveSnapshot(projectId, ObjectType.TABLE, event.getObjectName(),
                            event.getPayload());
                }
                break;
            case COLUMN:
            case INDEX: {
                TableMeta table = sourceMeta.table(event.getObjectName());
                if (table != null) {
                    stateWriter.saveSnapshot(projectId, ObjectType.TABLE, table.getName(), table);
                }
                break;
            }
            case VIEW:
                if (dropped) {
                    stateWriter.deleteSnapshot(projectId, ObjectType.VIEW, event.getObjectName());
                } else {
                    stateWriter.saveSnapshot(projectId, ObjectType.VIEW, event.getObjectName(),
                            event.getPayload());
                }
                break;
            case PROCEDURE:
            case FUNCTION:
                if (dropped) {
                    stateWriter.deleteSnapshot(projectId, ObjectType.PROCEDURE,
                            event.getObjectName());
                } else {
                    stateWriter.saveSnapshot(projectId, ObjectType.PROCEDURE,
                            event.getObjectName(), event.getPayload());
                }
                break;
            default:
                break;
        }
    }

    /**
     * Ensures the target table is present before data sync runs.
     *
     * <p>This is a safety net for when the target table has been manually dropped or the
     * metadata snapshot is out of sync with reality. Rather than failing the data sync with a
     * "table doesn't exist" error, we try an idempotent CREATE — if the table already exists the
     * database returns immediately, otherwise it gets built from the source definition using the
     * same DDL generator that structure sync uses (types, keys, indexes all correct).
     *
     * <p>When the cycle already listed the target's tables, a name found in that listing skips
     * the CREATE outright: without the listing every table of every poll paid one doomed CREATE
     * TABLE (plus one CREATE INDEX per secondary index) that existed only to be rejected by the
     * target with "already exists". A table absent from the listing takes the CREATE path and is
     * added to the set, so the listing stays true for the rest of the cycle.
     *
     * @return the table definition to use for the rest of the cycle — the full re-read when this
     *     call had to build it, so later steps see its indexes — and whether this call created
     *     the table; null on failure.
     */
    private EnsuredTarget ensureTargetTableExists(TableMeta table, Connection sourceConn,
                                                  Connection targetConn,
                                                  SyncContext ctx, SyncResult result,
                                                  Set<String> knownTargetTables) {
        String targetTable = ctx.getConfig().targetTableName(table.getName());
        if (knownTargetTables != null && knownTargetTables.contains(targetTable.toUpperCase())) {
            // 名字在清单里只证明"有这张表"，不证明形状对：手工预建的表可能缺列或类型不对。
            return verifyShapeIfNeeded(table, targetConn, ctx, result, targetTable)
                    ? new EnsuredTarget(table, false) : null;
        }
        table = completeDefinitionForCreate(table, sourceConn, ctx);
        ChangeEvent createEvent = ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE,
                table.getName(), "Auto-created before data sync (target table missing)", table);
        StructureSyncService.ApplyOutcome outcome =
                structureSync.apply(targetConn, createEvent, ctx);

        // ApplyOutcome.applied：ok→true, skipped→false（detail是原因）, failed→false（error是原因）
        if (!outcome.applied && outcome.error != null) {
            String msg = "Failed to create target table " + targetTable + ": " + outcome.error;
            stateWriter.recordLog(ctx.projectId(), ObjectType.TABLE, table.getName(),
                    ChangeType.ERROR, msg, false, 0);
            result.addError(table.getName() + ": " + outcome.error);
            return null;
        }

        // 只在真的创建了的时候记日志、更新快照。
        // executeIdempotentCreate 在表已存在时会返回 "Table already present: ..."，那种情况跳过记录。
        boolean created = outcome.applied && outcome.detail != null
                && outcome.detail.startsWith("Created table");
        if (created) {
            log.info("Auto-created missing target table {}", targetTable);
            stateWriter.recordLog(ctx.projectId(), ObjectType.TABLE, table.getName(),
                    ChangeType.CREATE, outcome.detail, true, 0);
            // An index that failed during creation is only queued in memory; the snapshot is
            // what keeps it detectable after a restart, so it waits until the queue is empty.
            if (!structureSync.hasPendingIndexes(ctx.projectId(), table.getName())) {
                stateWriter.saveSnapshot(ctx.projectId(), ObjectType.TABLE, table.getName(), table);
            }
            verifiedTables.add(verifiedKey(ctx, targetTable));
        } else if (outcome.applied && outcome.detail != null
                && outcome.detail.startsWith("Table already present")) {
            // 表已存在但不是我们这轮建的：核对列形状，不合规就拦住数据同步。
            if (!verifyShapeIfNeeded(table, targetConn, ctx, result, targetTable)) {
                return null;
            }
        }
        // 不管这轮是新建的还是本来就在（清单读取失败时才走到这），都登记进清单，
        // 后面的表若再引用它就不必重复发 CREATE。
        if (knownTargetTables != null) {
            knownTargetTables.add(targetTable.toUpperCase());
        }
        return new EnsuredTarget(table, created);
    }

    /** The definition the data phase must use, and whether this cycle built the target table. */
    private record EnsuredTarget(TableMeta table, boolean created) {
    }

    /**
     * The definition handed to an auto-CREATE.
     *
     * <p>A structure-off cycle reads tables without their index, foreign-key and comment
     * detail; building the target from that shape would silently create the table without the
     * indexes and comments structure sync normally adds — and snapshot it as converged. Re-read
     * the one table that is actually about to be created (rare: only when the target table has
     * vanished), so the CREATE is identical to what structure sync would have issued.
     */
    TableMeta completeDefinitionForCreate(TableMeta table, Connection sourceConn,
                                          SyncContext ctx) {
        if (ctx.getConfig().isSyncStructure()) {
            return table;
        }
        try {
            TableMeta full = ctx.getSourceReader().readTable(sourceConn, ctx.getSourceSchema(),
                    table.getName());
            return full != null ? full : table;
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not re-read the full definition of '{}'; creating it from the"
                    + " reduced metadata: {}", table.getName(), e.getMessage());
            return table;
        }
    }

    /**
     * Verifies that an existing target table's columns can receive the source table, unless
     * this process already verified that pair.
     *
     * @return false (with an error logged and added to the result) when the shape is
     *     incompatible; true when compatible or the metadata query itself could not run
     *     (the check is a safety net, so a driver that cannot answer must not block sync).
     */
    private boolean verifyShapeIfNeeded(TableMeta table, Connection targetConn, SyncContext ctx,
                                        SyncResult result, String targetTable) {
        String key = verifiedKey(ctx, targetTable);
        if (verifiedTables.contains(key)) {
            return true;
        }
        String schema = ctx.getTargetSchema();
        if (schema != null && schema.isBlank()) {
            schema = null;
        }
        String reason;
        try {
            reason = TargetTableVerifier.verify(targetConn, schema, targetTable, table,
                    ctx.effectiveTargetType());
        } catch (SQLException e) {
            log.debug("Could not verify target table {} columns: {}", targetTable, e.getMessage());
            return true;
        }
        if (reason != null) {
            reason = repairMissingColumns(targetConn, ctx, table, targetTable, schema, reason);
        }
        if (reason != null) {
            log.error("Project '{}': {}", ctx.projectName(), reason);
            stateWriter.recordLog(ctx.projectId(), ObjectType.TABLE, table.getName(),
                    ChangeType.ERROR, reason, false, 0);
            result.addError(table.getName() + ": " + reason);
            return false;
        }
        warnAboutExtraNotNullColumns(targetConn, ctx, table, targetTable, schema);
        verifiedTables.add(key);
        return true;
    }

    /**
     * Names target columns that no insert can fill — as a warning, never a block.
     *
     * <p>A {@code BEFORE INSERT} trigger can fill such a column (a DBA-maintained audit
     * column is the usual shape), and that deployment syncs fine today. Blocking would stop
     * working syncs to catch broken ones, so the hint rides along in the log — once per table
     * per process, because this runs right before the verdict is cached — and the inserts
     * themselves stay the judge.
     */
    private void warnAboutExtraNotNullColumns(Connection targetConn, SyncContext ctx,
                                              TableMeta table, String targetTable, String schema) {
        try {
            List<String> extras = TargetTableVerifier.extraNotNullColumnsWithoutDefault(
                    targetConn, schema, targetTable, table, ctx.effectiveTargetType());
            if (!extras.isEmpty()) {
                log.warn("Project '{}': target table {} has column(s) {} declared NOT NULL with"
                        + " no default that the source does not provide; inserts that omit them"
                        + " fail unless a trigger fills them. Drop them, give them a default, or"
                        + " add them to the source table.",
                        ctx.projectName(), targetTable, String.join(", ", extras));
            }
        } catch (SQLException e) {
            log.debug("Could not inspect extra columns of target table {}: {}",
                    targetTable, e.getMessage());
        }
    }

    /**
     * Adds the source columns a pre-existing target table lacks, then re-checks the shape.
     *
     * <p>Without this the shape check is a dead end for a table the structure phase never sees:
     * the structural diff compares the source against its snapshot, so a target table altered
     * behind Jync's back (or created by an older version) shows no diff, and the data phase
     * reported the same missing column every cycle while the advice it printed — "let structure
     * sync recreate it" — was something structure sync never did: it only issues CREATE, which
     * an existing table rejects.
     *
     * <p>Only additive repairs are attempted. A wrong type or a {@code GENERATED ALWAYS}
     * identity column keeps its reason and stays the user's call. With structure sync off
     * nothing is touched at all: the point of that switch is that Jync does not modify the
     * target's schema.
     *
     * @return null when the table is now compatible, otherwise the remaining reason
     */
    private String repairMissingColumns(Connection targetConn, SyncContext ctx, TableMeta table,
                                        String targetTable, String schema, String reason) {
        if (!ctx.getConfig().isSyncStructure()) {
            return reason;
        }
        try {
            List<String> added = structureSync.addMissingColumns(targetConn, ctx, targetTable, table);
            if (added.isEmpty()) {
                // 没有缺列可补（类型不符、身份列不合规之类），原判不变。
                return reason;
            }
            stateWriter.recordLog(ctx.projectId(), ObjectType.COLUMN, table.getName(),
                    ChangeType.CREATE, "Added missing column(s) " + String.join(", ", added)
                            + " to " + targetTable, true, 0);
        } catch (SQLException e) {
            log.warn("Project '{}': could not add the missing column(s) of {}: {}",
                    ctx.projectName(), targetTable, e.getMessage());
            return reason + " (adding the missing column(s) failed: " + e.getMessage() + ")";
        }
        return describeShapeBlocker(targetConn, schema, table, targetTable, ctx);
    }

    /** Re-reads the verdict after a repair attempt; null means the table is usable now. */
    private String describeShapeBlocker(Connection targetConn, String schema, TableMeta table,
                                        String targetTable, SyncContext ctx) {
        try {
            return TargetTableVerifier.verify(targetConn, schema, targetTable, table,
                    ctx.effectiveTargetType());
        } catch (SQLException e) {
            // 复核不了就以「可用」论处：这是安全网，驱动答不上来不该拦住同步。
            log.debug("Could not re-verify target table {} columns: {}", targetTable,
                    e.getMessage());
            return null;
        }
    }

    /**
     * A verdict is about one concrete target table, so the target schema is part of the key:
     * the same table name in another schema is another table. Without it, pointing the project
     * at a different schema reuses a verdict the verifier never issued for that table, and the
     * shape check — the repair path included — is skipped there until the process restarts.
     */
    private static String verifiedKey(SyncContext ctx, String targetTable) {
        String schema = ctx.getTargetSchema();
        String schemaPart = schema == null || schema.isBlank()
                ? "" : schema.toUpperCase(Locale.ROOT);
        return ctx.projectId() + ":" + schemaPart + ":" + targetTable.toUpperCase(Locale.ROOT);
    }

    /**
     * Drops the cached shape verdict for a table whose data sync just failed.
     *
     * <p>The verdict says "verified once", not "verified now": an administrator migration that
     * drops a column afterwards leaves it true only in the cache. Without this eviction the
     * table fails on the same JDBC error every cycle and the missing-column repair — the point
     * of the shape check when structure sync is on — never runs again until restart. The cost
     * is one extra {@code getColumns} on the next cycle, and only for a table that is already
     * failing.
     */
    private void forgetVerifiedShape(SyncContext ctx, TableMeta table) {
        verifiedTables.remove(verifiedKey(ctx,
                ctx.getConfig().targetTableName(table.getName())));
    }

    /**
     * Names, once per process, every selected keyed table whose FULL_COMPARE cycle is too
     * much: over the row cap, or too heavy for the task's current poll interval.
     *
     * <p>Advisory only: scheduling and the data phase are untouched. Keyless tables are
     * skipped because recurring FULL_COMPARE on them is skipped after the one-time seed.
     */
    private void warnOversizedFullCompareTables(DatabaseMeta sourceMeta, SyncContext ctx) {
        if (!ctx.getConfig().isSyncData()
                || ctx.getConfig().getCaptureStrategyOrDefault()
                        != CaptureStrategy.FULL_COMPARE) {
            return;
        }
        long limit = properties.getFullCompareMaxRows();
        long intervalMs = FullCompareLoadEstimator.effectiveIntervalMs(
                ctx.getConfig(), properties.getPollInterval());
        for (TableMeta table : sourceMeta.getTables()) {
            if (!ctx.getConfig().includesTable(table.getName())
                    || !table.hasPrimaryKey()
                    || table.getApproximateRowCount() == null) {
                continue;
            }
            long rows = table.getApproximateRowCount();
            boolean overCap = rows > limit;
            if (!overCap && !FullCompareLoadEstimator.isHeavy(rows, intervalMs)) {
                continue;
            }
            // The reason is part of the key: a table first named as merely heavy should be
            // named again if it later crosses the cap.
            String key = ctx.projectId() + ":"
                    + table.getName().toUpperCase(java.util.Locale.ROOT)
                    + (overCap ? ":over" : ":heavy");
            if (!warnedOversizedFullCompare.add(key)) {
                continue;
            }
            long recommendedMs = FullCompareLoadEstimator.recommendedIntervalMs(rows);
            if (overCap) {
                log.warn("Project '{}' uses FULL_COMPARE and table '{}' has about {} rows,"
                                + " over the {} row cap; at the current {} ms interval each"
                                + " cycle reads the whole table. Suggested interval: at least"
                                + " {} ms. Use incremental polling or log CDC, or raise"
                                + " sync.full-compare-max-rows if that load is intended.",
                        ctx.projectName(), table.getName(), rows, limit, intervalMs,
                        recommendedMs);
            } else {
                log.warn("Project '{}' uses FULL_COMPARE and table '{}' has about {} rows;"
                                + " at the current {} ms interval each cycle reads and writes"
                                + " the whole table. Suggested interval: at least {} ms."
                                + " Consider incremental polling or log CDC.",
                        ctx.projectName(), table.getName(), rows, intervalMs, recommendedMs);
            }
        }
    }

    /**
     * Says once per table why "同步删除" cannot run for it.
     *
     * <p>{@link DataSyncService#deletionDetectionSkipReason} answers with the reason or null;
     * the first non-null answer becomes a WARN and one change-log entry, so the skip is
     * visible both in the log and on the audit page without repeating every poll.
     */
    private void warnIfDeletionsAreSkipped(SyncContext ctx, TableMeta table) {
        String reason = dataSync.deletionDetectionSkipReason(table, ctx.getConfig());
        if (reason == null) {
            return;
        }
        String key = ctx.projectId() + ":" + table.getName().toUpperCase(java.util.Locale.ROOT);
        if (!warnedDeletionSkips.add(key)) {
            return;
        }
        String detail = "Deletion sync for " + table.getName() + " is skipped: " + reason
                + ". Rows deleted at the source stay on the target. Raise"
                + " sync.full-compare-max-rows if the table is small enough for an in-memory"
                + " comparison, raise sync.delete-compare-max-rows to compare it one key page"
                + " at a time (one extra full scan of the target per cycle), or use log-based"
                + " CDC, which carries deletes.";
        log.warn("Project '{}': {}", ctx.projectName(), detail);
        // INFO, success=true: nothing failed — the operator's configuration just cannot
        // deliver deletes for this table, and that belongs on the record.
        stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(),
                ChangeType.INFO, detail, true, 0);
    }

    /** Synchronizes rows for every selected table, one table at a time. */
    private void syncData(DatabaseMeta sourceMeta, Connection sourceConn, Connection targetConn,
                          SyncContext ctx, SyncResult result, Set<String> blockedTables) {
        // CDC 项目的数据只允许从基线全量 + binlog 回放两条路进目标库:常规轮询周期若也
        // 跑数据阶段,会和流式回放双写同一张表,DELETE 语义直接冲突(B6/B8)。
        CaptureStrategy strategy = ctx.getConfig().getCaptureStrategyOrDefault();
        if (strategy.isLogCdc() && !ctx.isCdcBaselineRun()) {
            log.info("Project {} uses {} capture; skipping polling data phase",
                    ctx.getProject() == null ? "?" : ctx.getProject().getId(), strategy);
            return;
        }
        // Scoped to the tables this cycle actually writes, so persistent per-table toggles
        // (SQL Server) cannot touch unrelated tables in a shared target database.
        List<String> toggledTables = ctx.getConfig().isDisableTargetConstraints()
                ? sourceMeta.getTables().stream()
                        .filter(t -> ctx.getConfig().includesTable(t.getName()))
                        .map(t -> ctx.getConfig().targetTableName(t.getName()))
                        .collect(java.util.stream.Collectors.toList())
                : List.of();
        List<String> disableStatements = ctx.getConfig().isDisableTargetConstraints()
                ? ctx.getTargetDialect().getConstraintToggleStatements(
                        ctx.getTargetSchema(), toggledTables, true)
                : List.of();
        DdlExecutor executor = new DdlExecutor(targetConn);
        for (String sql : disableStatements) {
            // Per table, best effort: one table the target refuses must not leave the rest
            // with foreign keys enforced during the load.
            executor.executeQuietly(sql);
        }

        // 目标端表清单每周期只读一次：命中的表跳过每轮一次的幂等 CREATE（那是一批注定被
        // 目标库拒绝的 DDL 往返）。清单本身读不出来时置空，退回到逐表 CREATE 的老路。
        // 读取必须留在下面的 try 之内：它只声明 SQLException，驱动抛出的未检查异常若在
        // try 之外穿出，恢复约束的 finally 就永远不执行，连接会带着 NOCHECK 回到池里。
        Set<String> knownTargetTables = null;
        try {
            try {
                knownTargetTables = new HashSet<>();
                for (String name : ctx.getTargetReader()
                        .listTableNames(targetConn, ctx.getTargetSchema())) {
                    knownTargetTables.add(name.toUpperCase());
                }
            } catch (SQLException e) {
                log.debug("Could not list target tables for project '{}'; falling back to per-table"
                        + " idempotent CREATE: {}", ctx.projectName(), e.getMessage());
                knownTargetTables = null;
            }

            // Parent-first order is derived from the source foreign-key dictionary; a data-only
            // cycle reads that dictionary only when the target loads with its constraints
            // enforced (see readSourceMetadata). Otherwise every table has zero foreign keys
            // and this sort degrades to the source listing order, which is harmless there.
            for (TableMeta table : structureSync.sortByDependency(sourceMeta.getTables())) {
                if (!ctx.getConfig().includesTable(table.getName())) {
                    continue;
                }
                // Backstop for tables that write nothing (empty result) yet still take time:
                // deep write paths renew at every commit, but a table with no commits reaches
                // none, so renew once per table as well. A failed renewal throws and aborts the
                // whole cycle; processed tables keep their advanced cursors and the rest are
                // picked up by whichever instance holds the lock next.
                ctx.requireLease();
                try {
                    syncOneTable(table, sourceConn, targetConn, ctx, result, blockedTables,
                            knownTargetTables);
                } catch (StopRequestedException | LockLostException e) {
                    // Not a table failure: the whole cycle must stop here.
                    throw e;
                } catch (RuntimeException e) {
                    // A mapping or dialect defect on one table must not stop every table after
                    // it. On a hundred-table database one bad table would otherwise turn into a
                    // whole-project outage. Structure sync already isolates per-object failures;
                    // the data phase gets the same contract.
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    log.error("Data sync failed for table '{}' in project '{}': {}",
                            table.getName(), ctx.projectName(), msg, e);
                    result.addError(table.getName() + ": " + msg);
                    stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(),
                            ChangeType.ERROR, msg, false, 0);
                    forgetVerifiedShape(ctx, table);
                }
            }
        } finally {
            if (!disableStatements.isEmpty()) {
                // Restore enforcement, even when a table failed part-way through, and table by
                // table: a driver abandons the rest of a multi-statement batch on the first
                // failure, so one bad table must not leave every later table with checks off.
                // This must not be quiet: a failed re-enable would return the connection to the
                // pool with checks still off, so the next borrower (this app's next cycle, or
                // any other client of the pool) would silently skip foreign-key enforcement.
                String failed = null;
                for (String sql : ctx.getTargetDialect().getConstraintToggleStatements(
                        ctx.getTargetSchema(), toggledTables, false)) {
                    try {
                        executor.execute(sql, false);
                    } catch (SQLException e) {
                        log.error("Could not re-enable target constraints for project '{}': {}",
                                ctx.projectName(), e.getMessage());
                        failed = e.getMessage();
                    }
                }
                if (failed != null) {
                    log.error("Aborting the physical connection so it cannot be recycled with"
                            + " constraint checks still disabled");
                    abortPhysicalConnection(targetConn);
                }
            }
        }
    }

    /**
     * Kills the underlying physical connection of a pooled handle ({@code Connection.abort},
     * JDBC 4.1). A pooled proxy's {@code close()} would normally recycle it; an aborted
     * handle is detected dead on recycle and evicted instead of being handed out again.
     */
    private void abortPhysicalConnection(Connection conn) {
        try {
            conn.abort(Runnable::run);
        } catch (SQLException | RuntimeException abortError) {
            log.error("Aborting the poisoned connection also failed: {}",
                    abortError.getMessage());
        }
    }

    private void syncOneTable(TableMeta table, Connection sourceConn, Connection targetConn,
                              SyncContext ctx, SyncResult result, Set<String> blockedTables,
                              Set<String> knownTargetTables) {
        long tableStart = System.currentTimeMillis();
        SyncProgress progress = stateWriter.loadOrCreateProgress(ctx.projectId(), table.getName());
        CursorStrategy strategy = cursorResolver.resolve(table, ctx.getConfig());
        if (strategy.getKind() == CursorStrategy.Kind.NONE
                && table.getApproximateRowCount() == null) {
            // The resolver fell through every cursor candidate and stopped at the step whose
            // answer depends on the table's size — which this cycle did not fetch, because
            // nothing else reads it (see readSourceMetadata). Pay the catalog round trip for
            // this one table and let the resolver decide again: without it a small keyless
            // table is parked as "size unknown, too large to compare" and never re-read,
            // where before it was fully compared every cycle.
            try {
                table.setApproximateRowCount(ctx.getSourceReader()
                        .estimateRowCount(sourceConn, ctx.getSourceSchema(), table.getName()));
                strategy = cursorResolver.resolve(table, ctx.getConfig());
            } catch (SQLException e) {
                log.debug("Row estimate failed for {}: {}", table.getName(), e.getMessage());
            }
        }

        // 目标表可能被手动删除了，或者快照与实际状态不同步。
        // 在开始数据同步前确认一下，缺失就用源表结构建一张，避免后续 upsert 整批失败。
        // The returned definition is the one the CREATE actually used: a structure-off cycle
        // read the reduced shape, so the deferred-index step below would otherwise see no
        // indexes and silently build none.
        EnsuredTarget ensured = ensureTargetTableExists(table, sourceConn, targetConn, ctx, result,
                knownTargetTables);
        if (ensured == null) {
            return;
        }
        table = ensured.table();
        if (ensured.created()) {
            // The target table Jync just built holds nothing, so the stored progress describes
            // a target that no longer exists: rows below the cursor are missing and an
            // incremental window never looks back. Start this table over as a first load —
            // which also files its rows as inserts instead of falling into the phase heuristic
            // reserved for revisions.
            log.info("Target table {} was created this cycle; resetting its progress so it is"
                    + " loaded from scratch", ctx.getConfig().targetTableName(table.getName()));
            progress = stateWriter.forceReload(progress);
        }

        boolean firstLoad = progress.getInitialLoadDone() == null || !progress.getInitialLoadDone();
        if (firstLoad && ctx.getConfig().isTruncateBeforeInitialLoad()) {
            try {
                dataSync.truncateTarget(targetConn, table, ctx);
            } catch (SQLException e) {
                // 清空失败就不能继续装数据：旧行会和新数据混在一起，用户要求清掉的内容
                // 悄悄留下了。游标不动，下一轮重试整张表。
                String msg = "Truncate before initial load failed: " + e.getMessage();
                log.error("{}: {}", table.getName(), msg);
                result.addError(table.getName() + ": " + msg);
                stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(),
                        ChangeType.ERROR, msg, false, 0);
                return;
            }
        }

        // 游标只能证明"我们读到哪儿了"，证明不了"目标真的收到了"。定期核对一次行数，
        // 目标少了就把游标清掉，让下一轮重新全量读一遍。
        // 用审计返回的实例继续：一旦它清了游标，修复是在自己的事务里提交的，
        // 只有返回的那个实例带着新版本号，旧引用再写就会撞乐观锁。
        AuditedProgress audited = auditRowCounts(table, sourceConn, targetConn, strategy,
                progress, ctx);
        progress = audited.progress();
        // The repair this cycle's audit performed, if any. It is reported exactly once: folded
        // into the entry that reports the reload it triggered, or — when there is no such entry
        // — on its own. Both paths null it out, which is what keeps one repair off the log twice.
        AuditRepair repair = audited.repair();

        DataSyncService.TableSyncResult tableResult = dataSync.syncTable(
                sourceConn, targetConn, table, strategy, progress, ctx);

        if (tableResult.isSkipped()) {
            // Nothing will report the reload, so the repair explains the vanished cursor itself.
            reportAuditRepair(ctx, table, repair);
            // Previously this was a debug-only line: a table silently standing still looked
            // identical to a table with nothing to do. Carry it on the result so the run
            // summary and the sync-now response name the table and the reason.
            log.info("Skipping data sync for {}: {}", table.getName(), tableResult.getSkipReason());
            // No load is coming for this table, so the indexes held back "until the load"
            // must be built now: a skipped table would otherwise keep a created table with
            // its secondary indexes missing for as long as the skip lasts. Only while the
            // first load is still outstanding: once it completed the indexes were settled,
            // and probing again every cycle would issue a failing CREATE INDEX per index.
            if (firstLoad) {
                structureSync.createDeferredIndexes(targetConn, ctx, table);
            }
            result.addSkipped(table.getName(), tableResult.getSkipReason());
            return;
        }

        if (!tableResult.isSuccess()) {
            // The failure is filed below; the repair that cleared the cursor needs its own line
            // or the next cycle's full load would look like it came from nowhere.
            reportAuditRepair(ctx, table, repair);
            result.addError(table.getName() + ": " + tableResult.getError());
            stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(),
                    ChangeType.ERROR, tableResult.getError(), false,
                    System.currentTimeMillis() - tableStart);
            // The cached "shape is fine" verdict may be why this failed: a column dropped on
            // the target after verification makes every write fail while the check is skipped.
            // Drop it so the next cycle re-checks (and, with structure sync on, re-attempts the
            // missing-column repair) instead of trusting a verdict reality has outgrown.
            forgetVerifiedShape(ctx, table);
            // The cursor is intentionally left untouched so this window is retried.
            return;
        }

        if (firstLoad) {
            // Explicit-id bulk loads leave a freshly created identity/sequence at 1; advance it
            // past the highest id now in the table. Best effort and non-fatal.
            dataSync.reseedIdentityAfterInitialLoad(targetConn, table, ctx);
        }

        int deleted = 0;
        if (ctx.getConfig().isSyncDeletes()) {
            warnIfDeletionsAreSkipped(ctx, table);
            try {
                deleted = dataSync.syncDeletions(sourceConn, targetConn, table, ctx);
            } catch (SQLException e) {
                log.warn("Deletion sync failed for {}: {}", table.getName(), e.getMessage());
                result.addError(table.getName() + " (deletes): " + e.getMessage());
            }
        }

        // Build the secondary indexes that were held back while the rows arrived. Repeated
        // after a resumed load (idempotent), and deliberately placed before the cursor is
        // advanced: a crash between the load and this point re-runs the idempotent load
        // instead of recording the table as done with its indexes still missing.
        if (tableResult.isInitialLoad()) {
            structureSync.createDeferredIndexes(targetConn, ctx, table);
        }

        // Advance the cursor only now that the target has committed the data. This ordering is
        // the core of the crash- and concurrency-safety argument.
        try {
            stateWriter.advanceCursor(progress, tableResult, strategy);
        } catch (OptimisticLockingFailureException e) {
            // 本轮周期里这行被别人写过（比如手工重置进度）。那个事务已经提交，
            // 重读当前行再推进，别让目标已经收到的数据下一轮再全量付一遍。
            log.warn("Cursor row for {} was written concurrently; re-reading before advancing: {}",
                    table.getName(), e.getMessage());
            progress = stateWriter.loadOrCreateProgress(ctx.projectId(), table.getName());
            stateWriter.advanceCursor(progress, tableResult, strategy);
        }

        result.setTablesProcessed(result.getTablesProcessed() + 1);
        long changed = tableResult.getRowsChanged();
        // Gate on rows the target actually changed. A full-compare table re-upserts every row
        // on every cycle, and each of those upserts is a no-op when nothing moved — logging the
        // scan size instead would post an identical "51 row(s)" entry every couple of seconds.
        if (changed > 0) {
            long inserted = tableResult.getRowsInserted();
            long updated = tableResult.getRowsUpdated();
            // Rows the target changed without saying how. Attribute them by phase, which is the
            // best available guess: an initial load into an empty table is all inserts, and a
            // later window is more often revisions of rows already delivered.
            long unclassified = tableResult.getRowsChangedUnclassified();
            if (unclassified > 0) {
                if (tableResult.isInitialLoad()) {
                    inserted += unclassified;
                } else {
                    updated += unclassified;
                }
            }

            // One entry per kind of write that actually happened, so a cycle that inserts some
            // rows and revises others is not filed wholesale under whichever came first.
            long durationMs = System.currentTimeMillis() - tableStart;
            if (inserted > 0) {
                result.setRowsInserted(result.getRowsInserted() + inserted);
                recordDataLog(ctx, table, strategy, tableResult, ChangeType.INSERT, inserted,
                        durationMs, causeOf(repair));
                repair = null;
            }
            if (updated > 0) {
                result.setRowsUpdated(result.getRowsUpdated() + updated);
                recordDataLog(ctx, table, strategy, tableResult, ChangeType.UPDATE, updated,
                        durationMs, causeOf(repair));
                repair = null;
            }
        }
        // A repair whose reload changed nothing still has to be on the record: it is the only
        // trace of why the cursor was cleared.
        reportAuditRepair(ctx, table, repair);
        if (deleted > 0) {
            result.setRowsDeleted(result.getRowsDeleted() + deleted);
            stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(),
                    ChangeType.DELETE, deleted + " row(s) removed to match source", true, 0,
                    deleted);
        }

        // Keep the table snapshot current even when only data moved, so a later structural
        // diff is not confused by a stale shape. Two guards:
        // - structure sync must be on. Refreshing while it is off would silently mark every
        //   source-side structural change as seen, so turning it on later would apply none
        //   of them — the diffs this refresh consumed are gone from the record.
        // - the table must not carry a failed or skipped structural event from this cycle.
        //   Its withheld snapshot is the retry record; writing the fresh shape here would
        //   undo exactly the protection applyStructuralChanges set up.
        if (ctx.getConfig().isSyncStructure() && !blockedTables.contains(table.getName())) {
            if (structureSync.hasPendingIndexes(ctx.projectId(), table.getName())) {
                // Same rule as the structure phase: while an index is queued, the snapshot must
                // not claim convergence. Deleting it also covers a table whose snapshot this
                // cycle already wrote (an auto-created table, whose deferred indexes are built
                // only after the load), so the next cycle re-detects and retries the index.
                stateWriter.deleteSnapshot(ctx.projectId(), ObjectType.TABLE, table.getName());
            } else {
                stateWriter.saveSnapshot(ctx.projectId(), ObjectType.TABLE, table.getName(), table);
            }
        }
    }

    /**
     * Checks that the target really holds every row the cursor claims to have delivered, and
     * clears the cursor when it does not.
     *
     * <p>This exists because an incremental cursor is a claim, not a proof. An identity cursor
     * only ever looks for higher ids, so rows that appear at the source <em>below</em> it are
     * invisible forever — which is precisely what happens when a source table is truncated and
     * reseeded, since {@code TRUNCATE} resets {@code AUTO_INCREMENT} and the replacement rows
     * land underneath a watermark the target never received. Nothing in the sync can notice
     * that by reading the window, so the row counts have to be compared directly.
     *
     * <p>Clearing the cursor is the whole repair: the next pass sees no cursor, takes the
     * full-load path, and re-upserts the table. Failures here are logged and swallowed — an
     * audit that cannot run must not stop the sync it was meant to protect.
     *
     * <p>Returns the progress instance the cycle must continue with: a repair commits in its
     * own transaction and bumps the row's version, so the instance handed in here is stale
     * afterwards whenever the cursor was actually cleared. The repair itself comes back with it,
     * unwritten, so the cycle can file it once rather than twice.
     */
    private AuditedProgress auditRowCounts(TableMeta table, Connection sourceConn,
                                           Connection targetConn, CursorStrategy strategy,
                                           SyncProgress progress, SyncContext ctx) {
        long interval = properties.getRowCountAuditIntervalMs();
        if (interval <= 0) {
            return AuditedProgress.unrepaired(progress);
        }
        // COUNT(*) is a full index scan on InnoDB, and the poll cycle is measured in seconds.
        // Throttling per table keeps the audit's cost proportional to its usefulness.
        String key = ctx.projectId() + ":" + table.getName();
        long now = System.currentTimeMillis();
        Long previous = lastRowCountAudit.get(key);
        if (previous != null && now - previous < interval) {
            return AuditedProgress.unrepaired(progress);
        }
        try {
            DataSyncService.RowCountAudit audit = dataSync.auditSyncedRowCounts(
                    sourceConn, targetConn, table, strategy, progress, ctx);
            // Stamp only after the audit actually ran: a pre-written stamp would throttle
            // away the retry for a whole interval when the check itself errored.
            lastRowCountAudit.put(key, now);
            if (audit == null || !audit.isTargetShort()) {
                return AuditedProgress.unrepaired(progress);
            }
            long missing = audit.getSourceRows() - audit.getTargetRows();
            // Read before clearing: clearCursor nulls this field on the same instance.
            String cursor = progress.getLastSyncValue();
            log.warn("Row count audit failed for {}: source has {} row(s) at or below cursor {}"
                            + " but target has {} — forcing a full reload of this table",
                    table.getName(), audit.getSourceRows(), cursor, audit.getTargetRows());
            progress = stateWriter.clearCursor(progress);
            // Deliberately not filed here: the reload that follows is this repair's visible
            // half, and filing both turned one backfilled row into two log lines.
            return new AuditedProgress(progress, new AuditRepair(missing,
                    audit.getSourceRows(), audit.getTargetRows(), cursor));
        } catch (SQLException e) {
            // Worth knowing about, but not worth failing the table over.
            log.warn("Row count audit could not run for {}: {}", table.getName(), e.getMessage());
        }
        return AuditedProgress.unrepaired(progress);
    }

    /**
     * Files a row-count repair for a cycle whose data phase leaves no entry to carry it.
     *
     * <p>A repair is one event: normally the reload writes an entry that says what arrived, and
     * the repair's counts ride along as its cause. This standalone entry covers the cycles where
     * that entry never comes — the reload changed nothing, was skipped, or failed — so the
     * cleared cursor always has a reason on the record.
     */
    private void reportAuditRepair(SyncContext ctx, TableMeta table, AuditRepair repair) {
        if (repair == null) {
            return;
        }
        stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(),
                ChangeType.INFO, repair.entry(), true, 0,
                (int) Math.min(repair.missing(), Integer.MAX_VALUE));
    }

    private static String causeOf(AuditRepair repair) {
        return repair == null ? null : repair.cause();
    }

    /** The progress to continue with, plus the repair this cycle's audit performed (if any). */
    private record AuditedProgress(SyncProgress progress, AuditRepair repair) {
        static AuditedProgress unrepaired(SyncProgress progress) {
            return new AuditedProgress(progress, null);
        }
    }

    /**
     * A row-count repair: what the audit found when it cleared the cursor.
     *
     * <p>Kept as data rather than written out immediately so the cycle can report the repair once
     * — as the cause of the entry that reports the reload, or on its own when there is none.
     */
    private record AuditRepair(long missing, long sourceRows, long targetRows, String cursor) {

        /** The whole entry, for a cycle whose repair produced no data entry of its own. */
        String entry() {
            return "Row count audit: target is missing " + missing + " row(s) already marked as"
                    + " synced (" + counts() + "). Cursor cleared; the table will be fully"
                    + " reloaded.";
        }

        /** The cause, appended to the entry that reports the reload this repair triggered. */
        String cause() {
            return " — row count audit: target was missing " + missing + " row(s) already marked"
                    + " as synced (" + counts() + "); cursor cleared and the table reloaded";
        }

        private String counts() {
            return "source " + sourceRows + " vs target " + targetRows
                    + " at or below cursor " + cursor;
        }
    }

    /**
     * Writes one audit entry for rows of a single kind — inserted or updated.
     *
     * <p>Full-compare rescans a whole table to find a handful of changes, so the scan size is
     * stated alongside the change count whenever the two differ: "1 row(s) (51 scanned)" says
     * what the cycle achieved and what it cost, where "1 row(s)" alone would hide the latter.
     *
     * <p>{@code cause} (nullable) appends the reason this load happened at all, which is how a
     * row-count repair is reported without a second entry for the same event.
     */
    private void recordDataLog(SyncContext ctx, TableMeta table, CursorStrategy strategy,
                               DataSyncService.TableSyncResult tableResult, ChangeType changeType,
                               long rows, long durationMs, String cause) {
        String scanned = scanDetail(rows, tableResult.getRowsChanged(),
                tableResult.getRowsWritten(), tableResult.isInitialLoad());
        String phase = tableResult.isInitialLoad() ? "Initial load: " : "Incremental sync: ";
        String verb = changeType == ChangeType.INSERT ? " inserted" : " updated";
        stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(), changeType,
                phase + rows + " row(s)" + verb + scanned + " via "
                        + strategy.getKind().name().toLowerCase() + " strategy"
                        + (strategy.getColumn() != null ? " on " + strategy.getColumn() : "")
                        + (cause != null ? cause : ""),
                true, durationMs, affectedRows(rows));
    }

    /**
     * A row count for {@code ChangeLog.affected_rows}, which is an INTEGER column: saturate
     * rather than wrap a multi-billion-row count into a negative number in the audit log.
     */
    private static Integer affectedRows(long rows) {
        return rows > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rows;
    }

    /**
     * The " (N scanned[, K already up to date])" detail, or empty when the scan changed as
     * many rows as it read.
     *
     * <p>An initial load can re-read rows the target already holds: a table recreated or
     * partly seeded in an earlier pass leaves rows whose values are identical, and the upsert
     * changes nothing for them (MySQL reports zero affected rows). Saying so keeps
     * "50 row(s) inserted (53 scanned)" from looking like a miscount next to a 53-row target.
     * Incremental windows and full-compare cycles rescan a margin or a whole table by design,
     * so the clause is initial-load only — there it would be per-cycle noise.
     */
    static String scanDetail(long rows, long changedRows, long scannedRows,
                             boolean initialLoad) {
        if (scannedRows <= rows) {
            return "";
        }
        String detail = scannedRows + " scanned";
        long upToDate = scannedRows - changedRows;
        if (initialLoad && upToDate > 0) {
            detail += ", " + upToDate + " already up to date";
        }
        return " (" + detail + ")";
    }

    /** Clears all progress and snapshots, forcing the next run to do a full reload. */
    public void resetProject(Long projectId) {
        stateWriter.resetProject(projectId);
        evictProjectCaches(projectId);
    }

    /**
     * Drops this project's in-memory throttle entries. They are all keyed
     * {@code projectId:...} and only ever grow otherwise — project deletion and table renames
     * would strand their entries for the life of the process. The two "already warned" sets
     * belong here for the same reason: a reset or a delete must not leave the project silent
     * on a note nobody has seen since.
     */
    public void evictProjectCaches(Long projectId) {
        String prefix = projectId + ":";
        lastRowCountAudit.keySet().removeIf(key -> key.startsWith(prefix));
        verifiedTables.removeIf(key -> key.startsWith(prefix));
        warnedOversizedFullCompare.removeIf(key -> key.startsWith(prefix));
        warnedDeletionSkips.removeIf(key -> key.startsWith(prefix));
        structureSync.evictProject(projectId);
    }
}
