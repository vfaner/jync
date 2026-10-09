package com.qqmu.jync.dto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;

/**
 * Per-project sync settings, persisted as JSON on {@link com.qqmu.jync.model.Project}.
 *
 * <p>An empty {@link #tables} set means "all tables discovered in the source", which is
 * the default and matches the UI's select-all behaviour. Explicit selections are stored
 * so newly created source tables are not silently picked up.
 */
@Getter
@Setter
public class SyncConfig {

    /** Selected table names. Empty means all tables. */
    private Set<String> tables = new LinkedHashSet<>();

    /** Selected view names. Empty means all views (when {@link #syncViews} is on). */
    private Set<String> views = new LinkedHashSet<>();

    /** Selected procedure/function names. Empty means all. */
    private Set<String> procedures = new LinkedHashSet<>();

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

    private Integer batchSize;

    /**
     * Explicit cursor column per table, e.g. {@code {"orders": "updated_at"}}.
     * Overrides auto-detection, which is a heuristic and can pick the wrong column.
     */
    private Map<String, String> cursorColumns = new HashMap<>();

    /** Table name mapping source -> target; unmapped tables keep their name. */
    private Map<String, String> tableNameMapping = new HashMap<>();

    /** User-edited DDL overrides, keyed by {@code TYPE:NAME}, for objects that cannot be auto-converted. */
    private Map<String, String> ddlOverrides = new HashMap<>();

    /** Temporarily disable target foreign keys during data load. */
    private boolean disableTargetConstraints = true;

    /** Truncate the target table before the initial full load. */
    private boolean truncateBeforeInitialLoad = false;

    public boolean includesTable(String tableName) {
        return tables.isEmpty() || containsIgnoreCase(tables, tableName);
    }

    public boolean includesView(String viewName) {
        return views.isEmpty() || containsIgnoreCase(views, viewName);
    }

    public boolean includesProcedure(String procName) {
        return procedures.isEmpty() || containsIgnoreCase(procedures, procName);
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        if (value == null) {
            return false;
        }
        for (String s : set) {
            if (s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
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
                    .computeIfAbsent(e.getValue().toUpperCase(), k -> new ArrayList<>())
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
