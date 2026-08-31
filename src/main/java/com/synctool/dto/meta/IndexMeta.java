package com.synctool.dto.meta;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** An index definition, normalized across products. */
@Getter
@Setter
public class IndexMeta {

    private String name;

    private String tableName;

    private boolean unique;

    /** Column names in index order. */
    private List<String> columns = new ArrayList<>();

    /** True when this index backs the primary key and must not be created separately. */
    private boolean primaryKey;

    /** Product-specific index kind, e.g. BTREE / HASH. May be null. */
    private String indexType;

    public String signature() {
        return String.join("|",
                String.valueOf(name).toUpperCase(),
                String.valueOf(unique),
                String.join(",", columns).toUpperCase());
    }

    @Override
    public String toString() {
        return (unique ? "UNIQUE " : "") + "INDEX " + name + " (" + String.join(", ", columns) + ")";
    }
}
