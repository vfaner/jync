package com.qqmu.jync.dto.meta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;

/** Everything read from one database in a single metadata pass. */
@Getter
@Setter
public class DatabaseMeta {

    private String schema;

    private List<TableMeta> tables = new ArrayList<>();

    private List<ViewMeta> views = new ArrayList<>();

    private List<ProcedureMeta> procedures = new ArrayList<>();

    /**
     * Names (upper-case) of objects the reader proved exist but could not load this pass
     * (a revoked grant, a transient dictionary error, a lock timeout). They are deliberately
     * NOT in the matching {@code tables/views/procedures} list, so they take no part in CREATE
     * or diff — but they must not be read as "vanished" either: the change detector skips them
     * when considering DROP events. Keeping their snapshots intact lets the next successful
     * pass diff normally.
     */
    private Set<String> unreadableTables = new HashSet<>();

    private Set<String> unreadableViews = new HashSet<>();

    private Set<String> unreadableProcedures = new HashSet<>();

    public void addUnreadableTable(String name) {
        if (name != null) {
            unreadableTables.add(name.toUpperCase());
        }
    }

    public void addUnreadableView(String name) {
        if (name != null) {
            unreadableViews.add(name.toUpperCase());
        }
    }

    public void addUnreadableProcedure(String name) {
        if (name != null) {
            unreadableProcedures.add(name.toUpperCase());
        }
    }

    public TableMeta table(String name) {
        return tables.stream()
                .filter(t -> t.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public ViewMeta view(String name) {
        return views.stream()
                .filter(v -> v.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public ProcedureMeta procedure(String name) {
        return procedures.stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}
