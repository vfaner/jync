package com.synctool.dto.meta;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** Everything read from one database in a single metadata pass. */
@Getter
@Setter
public class DatabaseMeta {

    private String schema;

    private String productName;

    private String productVersion;

    private List<TableMeta> tables = new ArrayList<>();

    private List<ViewMeta> views = new ArrayList<>();

    private List<ProcedureMeta> procedures = new ArrayList<>();

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
