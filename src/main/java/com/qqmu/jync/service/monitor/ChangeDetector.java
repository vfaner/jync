package com.qqmu.jync.service.monitor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;

import lombok.extern.slf4j.Slf4j;

/**
 * Diffs live source metadata against the stored snapshot and emits the DDL work to do.
 *
 * <p>The diff is deliberately conservative: DROP events are only produced when the project
 * opts in, because an object missing from a metadata read can also mean a permissions change
 * or a transient error, and dropping a target table is not recoverable.
 */
@Service
@Slf4j
public class ChangeDetector {

    private final MetadataSnapshotService snapshotService;

    public ChangeDetector(MetadataSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    /**
     * Compares the source's current shape with the last snapshot.
     *
     * @return events ordered so dependencies are created before the objects that use them
     */
    public List<ChangeEvent> detect(Long projectId, DatabaseMeta current, SyncConfig config) {
        List<ChangeEvent> events = new ArrayList<>();

        if (config.isSyncStructure()) {
            detectTableChanges(projectId, current, config, events);
        }
        if (config.isSyncViews()) {
            detectViewChanges(projectId, current, config, events);
        }
        if (config.isSyncProcedures()) {
            detectProcedureChanges(projectId, current, config, events);
        }

        events.sort((a, b) -> Integer.compare(a.getPriority(), b.getPriority()));
        if (!events.isEmpty()) {
            log.info("Detected {} structural change(s) for project {}", events.size(), projectId);
        }
        return events;
    }

    private void detectTableChanges(Long projectId, DatabaseMeta current, SyncConfig config,
                                    List<ChangeEvent> events) {
        Map<String, TableMeta> snapshots =
                snapshotService.loadAll(projectId, ObjectType.TABLE, TableMeta.class);
        Set<String> seen = new LinkedHashSet<>();

        for (TableMeta table : current.getTables()) {
            if (!config.includesTable(table.getName())) {
                continue;
            }
            String key = table.getName().toUpperCase();
            seen.add(key);
            TableMeta previous = snapshots.get(key);

            if (previous == null) {
                events.add(ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, table.getName(),
                        "New table with " + table.getColumns().size() + " column(s)", table));
                // The table's indexes come with the CREATE, so no separate index events.
                continue;
            }

            diffColumns(table, previous, events);
            if (config.isSyncIndexes()) {
                diffIndexes(table, previous, config, events);
            }
        }

        // Tables that vanished from the source.
        if (config.isAllowDrop()) {
            for (Map.Entry<String, TableMeta> e : snapshots.entrySet()) {
                if (!seen.contains(e.getKey()) && config.includesTable(e.getValue().getName())) {
                    events.add(ChangeEvent.of(ObjectType.TABLE, ChangeType.DROP,
                            e.getValue().getName(), "Table no longer exists in source",
                            e.getValue()));
                }
            }
        }
    }

    private void diffColumns(TableMeta current, TableMeta previous, List<ChangeEvent> events) {
        Map<String, ColumnMeta> currentCols = current.columnsByUpperName();
        Map<String, ColumnMeta> previousCols = previous.columnsByUpperName();

        for (Map.Entry<String, ColumnMeta> e : currentCols.entrySet()) {
            ColumnMeta col = e.getValue();
            ColumnMeta old = previousCols.get(e.getKey());
            if (old == null) {
                ChangeEvent event = ChangeEvent.of(ObjectType.COLUMN, ChangeType.CREATE,
                        current.getName(), "New column " + col.getName() + " " + col.getTypeName(), col);
                event.setDetailName(col.getName());
                events.add(event);
            } else if (!col.signature().equals(old.signature())) {
                ChangeEvent event = ChangeEvent.of(ObjectType.COLUMN, ChangeType.ALTER,
                        current.getName(),
                        "Column " + col.getName() + " changed: " + old + " -> " + col, col);
                event.setDetailName(col.getName());
                events.add(event);
            }
        }

        // Dropped columns are reported but only applied when the project allows drops; the
        // engine re-checks that flag, so emitting the event here is safe.
        for (Map.Entry<String, ColumnMeta> e : previousCols.entrySet()) {
            if (!currentCols.containsKey(e.getKey())) {
                ChangeEvent event = ChangeEvent.of(ObjectType.COLUMN, ChangeType.DROP,
                        current.getName(), "Column " + e.getValue().getName() + " removed from source",
                        e.getValue());
                event.setDetailName(e.getValue().getName());
                events.add(event);
            }
        }
    }

    private void diffIndexes(TableMeta current, TableMeta previous, SyncConfig config,
                             List<ChangeEvent> events) {
        Map<String, IndexMeta> currentIdx = byUpperName(current.secondaryIndexes());
        Map<String, IndexMeta> previousIdx = byUpperName(previous.secondaryIndexes());

        for (Map.Entry<String, IndexMeta> e : currentIdx.entrySet()) {
            IndexMeta index = e.getValue();
            IndexMeta old = previousIdx.get(e.getKey());
            if (old == null) {
                ChangeEvent event = ChangeEvent.of(ObjectType.INDEX, ChangeType.CREATE,
                        current.getName(), "New index " + index, index);
                event.setDetailName(index.getName());
                events.add(event);
            } else if (!index.signature().equals(old.signature())) {
                // An index cannot be altered in place; the engine drops and recreates it.
                ChangeEvent event = ChangeEvent.of(ObjectType.INDEX, ChangeType.ALTER,
                        current.getName(), "Index " + index.getName() + " redefined", index);
                event.setDetailName(index.getName());
                events.add(event);
            }
        }

        if (config.isAllowDrop()) {
            for (Map.Entry<String, IndexMeta> e : previousIdx.entrySet()) {
                if (!currentIdx.containsKey(e.getKey())) {
                    ChangeEvent event = ChangeEvent.of(ObjectType.INDEX, ChangeType.DROP,
                            current.getName(), "Index " + e.getValue().getName() + " removed",
                            e.getValue());
                    event.setDetailName(e.getValue().getName());
                    events.add(event);
                }
            }
        }
    }

    private Map<String, IndexMeta> byUpperName(List<IndexMeta> indexes) {
        Map<String, IndexMeta> map = new java.util.LinkedHashMap<>();
        for (IndexMeta i : indexes) {
            if (i.getName() != null) {
                map.put(i.getName().toUpperCase(), i);
            }
        }
        return map;
    }

    private void detectViewChanges(Long projectId, DatabaseMeta current, SyncConfig config,
                                   List<ChangeEvent> events) {
        Map<String, ViewMeta> snapshots =
                snapshotService.loadAll(projectId, ObjectType.VIEW, ViewMeta.class);
        Set<String> seen = new LinkedHashSet<>();

        for (ViewMeta view : current.getViews()) {
            if (!config.includesView(view.getName())) {
                continue;
            }
            String key = view.getName().toUpperCase();
            // Marked as seen BEFORE the definition check: the view demonstrably still exists,
            // so an unreadable body (a permissions hiccup, a transient dictionary error) must
            // not let the drop detection below conclude it vanished and destroy the target
            // view. Its stored snapshot stays untouched, so the signature diff fires again
            // once the body is readable.
            seen.add(key);
            // A view whose body could not be read cannot be recreated; skip it rather than
            // emit a CREATE that would fail with a null definition.
            if (view.getDefinition() == null || view.getDefinition().isBlank()) {
                log.debug("Skipping view {}: definition unavailable from source", view.getName());
                continue;
            }
            ViewMeta previous = snapshots.get(key);
            if (previous == null) {
                events.add(ChangeEvent.of(ObjectType.VIEW, ChangeType.CREATE, view.getName(),
                        "New view", view));
            } else if (!view.signature().equals(previous.signature())) {
                events.add(ChangeEvent.of(ObjectType.VIEW, ChangeType.ALTER, view.getName(),
                        "View definition changed", view));
            }
        }

        if (config.isAllowDrop()) {
            for (Map.Entry<String, ViewMeta> e : snapshots.entrySet()) {
                if (!seen.contains(e.getKey()) && config.includesView(e.getValue().getName())) {
                    events.add(ChangeEvent.of(ObjectType.VIEW, ChangeType.DROP,
                            e.getValue().getName(), "View no longer exists in source", e.getValue()));
                }
            }
        }
    }

    private void detectProcedureChanges(Long projectId, DatabaseMeta current, SyncConfig config,
                                        List<ChangeEvent> events) {
        Map<String, ProcedureMeta> snapshots =
                snapshotService.loadAll(projectId, ObjectType.PROCEDURE, ProcedureMeta.class);
        Set<String> seen = new LinkedHashSet<>();

        for (ProcedureMeta proc : current.getProcedures()) {
            if (!config.includesProcedure(proc.getName())) {
                continue;
            }
            String key = proc.getName().toUpperCase();
            // Seen before the body check, for the same reason as views: an unreadable body
            // means "cannot recreate right now", never "was dropped at the source".
            seen.add(key);
            if (proc.getDefinition() == null || proc.getDefinition().isBlank()) {
                log.debug("Skipping routine {}: source body unavailable (the account may lack "
                        + "rights to read routine source)", proc.getName());
                continue;
            }
            ProcedureMeta previous = snapshots.get(key);
            if (previous == null) {
                events.add(ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.CREATE, proc.getName(),
                        "New " + proc.getRoutineType().toLowerCase(), proc));
            } else if (!proc.signature().equals(previous.signature())) {
                events.add(ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.ALTER, proc.getName(),
                        proc.getRoutineType() + " body changed", proc));
            }
        }

        if (config.isAllowDrop()) {
            for (Map.Entry<String, ProcedureMeta> e : snapshots.entrySet()) {
                if (!seen.contains(e.getKey()) && config.includesProcedure(e.getValue().getName())) {
                    events.add(ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.DROP,
                            e.getValue().getName(), "Routine no longer exists in source", e.getValue()));
                }
            }
        }
    }
}
