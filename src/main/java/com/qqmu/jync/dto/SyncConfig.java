package com.qqmu.jync.dto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.util.CollectionUtil;

import lombok.Getter;
import lombok.Setter;

/**
 * Per-project sync settings, persisted as JSON on {@link com.qqmu.jync.model.Project}.
 *
 * <p>The object sets have two readings, told apart by {@link #objectSelectionExplicit}:
 * a legacy config (never saved through the picker, or written by an older version) treats an
 * empty set as "all objects discovered in the source", because that is the only sensible
 * default for a project whose selection was never expressed. Once the form has persisted a
 * selection, the empty set means "none of this kind" — unchecking everything is a choice,
 * and the cycle simply finds nothing to sync.
 */
@Getter
@Setter
// Tolerate unknown JSON keys on the class itself: the app-wide mapper is lenient
// (JacksonConfig), but this DTO must also survive a plain ObjectMapper — e.g. when a
// newer version's sync_config carries keys this version does not know yet.
@JsonIgnoreProperties(ignoreUnknown = true)
public class SyncConfig {

    /** Selected table names. Empty means all — or none when {@link #objectSelectionExplicit}. */
    private Set<String> tables = new LinkedHashSet<>();

    /** Selected view names. Empty means all — or none when {@link #objectSelectionExplicit}. */
    private Set<String> views = new LinkedHashSet<>();

    /** Selected procedure/function names. Empty means all — or none when {@link #objectSelectionExplicit}. */
    private Set<String> procedures = new LinkedHashSet<>();

    /**
     * True once the selection pickers have been saved, making an empty object set mean
     * "sync none of this kind" instead of the legacy "sync everything". Absent in configs
     * written before this field existed, which is exactly the legacy reading.
     */
    private boolean objectSelectionExplicit;

    private boolean syncStructure = true;
    private boolean syncData = true;
    private boolean syncIndexes = true;
    private boolean syncViews = true;
    private boolean syncProcedures = true;

    /** Apply DROP on the target when the object disappears from the source. */
    private boolean allowDrop = false;

    /** Propagate source row deletions. Requires a delete-detection strategy. */
    private boolean syncDeletes = false;

    /** Poll interval override in milliseconds; null falls back to the global default. */
    private Long pollIntervalMs;

    /** Optional Quartz cron expression; takes precedence over the poll interval. */
    private String cronExpression;

    /**
     * Rows per JDBC batch of this project's initial/full load; null falls back to the global
     * {@code sync.full-load-batch-size}. Incremental windows and CDC replay are not affected.
     */
    private Integer batchSize;

    /**
     * Parallel workers for this project's chunked full load; null falls back to the global
     * {@code sync.full-load-parallelism}. The effective value is further capped by the
     * connection pool. Any table with a primary key can be split: an integral leading column
     * arithmetically into ranges, other key shapes on boundaries sampled from the source.
     */
    private Integer fullLoadParallelism;

    /**
     * Explicit cursor column per table, e.g. {@code {"orders": "updated_at"}}.
     * Overrides auto-detection, which is a heuristic and can pick the wrong column.
     */
    private Map<String, String> cursorColumns = new HashMap<>();

    /** Table name mapping source -> target; unmapped tables keep their name. */
    private Map<String, String> tableNameMapping = new HashMap<>();

    /**
     * Ordered string-cleansing rules, applied to matching columns just before the value
     * is written to the target. Empty means every value is copied verbatim.
     */
    private List<ColumnRule> columnRules = new ArrayList<>();

    /** User-edited DDL overrides, keyed by {@code TYPE:NAME}, for objects that cannot be auto-converted. */
    private Map<String, String> ddlOverrides = new HashMap<>();

    /** Temporarily disable target foreign keys during data load. */
    private boolean disableTargetConstraints = true;

    /** Truncate the target table before the initial full load. */
    private boolean truncateBeforeInitialLoad = false;

    /**
     * Change-capture strategy (v1.5.0). Null in configs written by older versions —
     * always read through {@link #getCaptureStrategyOrDefault()}, never the raw getter.
     */
    private CaptureStrategy captureStrategy;

    /**
     * Idle poll interval in milliseconds for the polling log-CDC engines
     * (Oracle LogMiner / PostgreSQL logical slot / SQL Server CDC). Null falls back to
     * the engine default (LogMiner 1000 / PG 500 / SQL Server 1000). The push-based
     * MySQL binlog engine never polls and ignores this value. The consumer clamps it to
     * the 50ms safety floor.
     */
    private Long cdcIdlePollMs;

    /** Null-safe accessor: legacy configs (field absent) behave exactly like v1.4.0. */
    @JsonIgnore // derived value: keep it out of the persisted JSON, which stores captureStrategy only
    public CaptureStrategy getCaptureStrategyOrDefault() {
        return captureStrategy == null ? CaptureStrategy.POLLING : captureStrategy;
    }

    public boolean includesTable(String tableName) {
        return includes(tables, tableName);
    }

    public boolean includesView(String viewName) {
        return includes(views, viewName);
    }

    public boolean includesProcedure(String procName) {
        return includes(procedures, procName);
    }

    /** Empty set: all for a legacy config, none once an explicit selection was saved. */
    private boolean includes(Set<String> selection, String name) {
        if (selection.isEmpty()) {
            return !objectSelectionExplicit;
        }
        return CollectionUtil.containsIgnoreCase(selection, name);
    }

    /**
     * True when the saved selection explicitly names no object at all, so a sync cycle could
     * only ever be a no-op. A legacy empty selection does not qualify: it still means "all",
     * which is always something to sync.
     */
    public boolean hasNoSyncObjects() {
        return objectSelectionExplicit
                && tables.isEmpty() && views.isEmpty() && procedures.isEmpty();
    }

    /**
     * True when a polling cycle has no enabled category left to write: table structure and
     * data are both off (or no table is selected), and views/routines are off (or none is
     * selected). Index work only rides on table structure, so {@link #syncIndexes} alone
     * cannot produce a statement.
     *
     * <p>Only meaningful for the polling strategies ({@link CaptureStrategy#POLLING},
     * {@link CaptureStrategy#FULL_COMPARE}). A CDC consumer replays row events filtered by
     * the selected tables alone and ignores these switches after the initial baseline, so
     * callers must not use this to block a CDC start.
     */
    public boolean hasNoPollingWork() {
        boolean tableWork = (syncStructure || syncData) && mayContainObjects(tables);
        boolean viewWork = syncViews && mayContainObjects(views);
        boolean procedureWork = syncProcedures && mayContainObjects(procedures);
        return !tableWork && !viewWork && !procedureWork;
    }

    /** Legacy empty means "all discovered", so work may exist; explicit empty means none. */
    private boolean mayContainObjects(Set<String> selection) {
        return !objectSelectionExplicit || !selection.isEmpty();
    }

    public String targetTableName(String sourceTable) {
        for (Map.Entry<String, String> e : tableNameMapping.entrySet()) {
            if (e.getKey().equalsIgnoreCase(sourceTable)
                    && e.getValue() != null && !e.getValue().isBlank()) {
                return e.getValue();
            }
        }
        return sourceTable;
    }

    /**
     * Finds source tables that would be synced into the same target table.
     *
     * <p>Two mappings such as {@code A -> X} and {@code B -> X} make both sources write the
     * same target: rows interleave, cursors advance against the wrong table, and truncation
     * wipes one source's data — a configuration that must be refused rather than run.
     *
     * @param knownSourceTables the source tables actually present (or selected). When null or
     *                         empty, only the explicitly mapped source names are checked,
     *                         which still catches two mappings to one target.
     * @return human-readable conflict descriptions; an empty list means no collision
     */
    public List<String> tableMappingConflicts(Collection<String> knownSourceTables) {
        // Effective target per distinct source name, preserving the first spelling seen.
        LinkedHashMap<String, String> targetBySource = new LinkedHashMap<>();
        if (knownSourceTables != null && !knownSourceTables.isEmpty()) {
            for (String source : knownSourceTables) {
                if (source != null && !source.isBlank()) {
                    targetBySource.putIfAbsent(source.trim(), targetTableName(source.trim()));
                }
            }
        } else {
            for (String source : tableNameMapping.keySet()) {
                if (source != null && !source.isBlank()) {
                    targetBySource.putIfAbsent(source.trim(), targetTableName(source.trim()));
                }
            }
        }

        // Target name (upper-case) -> every source spelling that resolves to it. Identifiers
        // are compared case-insensitistically, matching targetTableName: this is deliberately
        // conservative, since a target that folds name case (MySQL on Windows, H2) would
        // collide even where a case-sensitive database would not.
        LinkedHashMap<String, List<String>> sourcesByTarget = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : targetBySource.entrySet()) {
            sourcesByTarget
                    .computeIfAbsent(e.getValue().toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(e.getKey());
        }

        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : sourcesByTarget.entrySet()) {
            if (e.getValue().size() > 1) {
                String target = targetTableName(e.getValue().get(0));
                conflicts.add("Source tables " + quoteJoined(e.getValue())
                        + " all sync to the same target table \"" + target + "\"");
            }
        }
        return conflicts;
    }

    private static String quoteJoined(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(i == names.size() - 1 ? " and " : ", ");
            }
            sb.append('"').append(names.get(i)).append('"');
        }
        return sb.toString();
    }

    public String cursorColumnFor(String table) {
        for (Map.Entry<String, String> e : cursorColumns.entrySet()) {
            if (e.getKey().equalsIgnoreCase(table)) {
                return e.getValue();
            }
        }
        return null;
    }

    public String ddlOverride(String type, String name) {
        return ddlOverrides.get(type + ":" + name);
    }
}
