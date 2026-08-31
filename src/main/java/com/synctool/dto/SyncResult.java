package com.synctool.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/** Aggregate outcome of one sync run, summarized into the task's last result. */
@Getter
@Setter
public class SyncResult {

    private boolean success = true;

    private int structureChanges;

    private int rowsInserted;

    private int rowsUpdated;

    private int rowsDeleted;

    private int tablesProcessed;

    private long durationMs;

    private List<String> errors = new ArrayList<>();

    private Map<String, Integer> rowsPerTable = new LinkedHashMap<>();

    public void addError(String message) {
        this.success = false;
        this.errors.add(message);
    }

    public void addTableRows(String table, int rows) {
        if (rows > 0) {
            rowsPerTable.merge(table, rows, Integer::sum);
        }
    }

    public int totalRows() {
        return rowsInserted + rowsUpdated + rowsDeleted;
    }

    public boolean hasChanges() {
        return structureChanges > 0 || totalRows() > 0;
    }

    public String summary() {
        if (!success) {
            return "FAILED: " + String.join("; ", errors.subList(0, Math.min(3, errors.size())));
        }
        if (!hasChanges()) {
            return "No changes";
        }
        return String.format("DDL=%d, rows +%d ~%d -%d, tables=%d, %dms",
                structureChanges, rowsInserted, rowsUpdated, rowsDeleted,
                tablesProcessed, durationMs);
    }

    public void merge(SyncResult other) {
        this.structureChanges += other.structureChanges;
        this.rowsInserted += other.rowsInserted;
        this.rowsUpdated += other.rowsUpdated;
        this.rowsDeleted += other.rowsDeleted;
        this.tablesProcessed += other.tablesProcessed;
        this.errors.addAll(other.errors);
        other.rowsPerTable.forEach((k, v) -> this.rowsPerTable.merge(k, v, Integer::sum));
        if (!other.success) {
            this.success = false;
        }
    }
}
