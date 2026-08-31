package com.synctool.dto.meta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;

/** A table definition together with its columns, keys and indexes. */
@Getter
@Setter
public class TableMeta {

    private String name;

    private String schema;

    private String remarks;

    private List<ColumnMeta> columns = new ArrayList<>();

    /** Primary key column names in key order. Empty when the table has no PK. */
    private List<String> primaryKeys = new ArrayList<>();

    private List<IndexMeta> indexes = new ArrayList<>();

    private List<ForeignKeyMeta> foreignKeys = new ArrayList<>();

    /** Approximate row count, used to decide whether full comparison is affordable. */
    private Long approximateRowCount;

    public Optional<ColumnMeta> column(String columnName) {
        return columns.stream()
                .filter(c -> c.getName().equalsIgnoreCase(columnName))
                .findFirst();
    }

    public Map<String, ColumnMeta> columnsByUpperName() {
        Map<String, ColumnMeta> map = new LinkedHashMap<>();
        for (ColumnMeta c : columns) {
            map.put(c.getName().toUpperCase(), c);
        }
        return map;
    }

    public List<String> columnNames() {
        return columns.stream().map(ColumnMeta::getName).collect(Collectors.toList());
    }

    public boolean hasPrimaryKey() {
        return primaryKeys != null && !primaryKeys.isEmpty();
    }

    /** Non-primary-key indexes, i.e. the ones worth creating as standalone indexes. */
    public List<IndexMeta> secondaryIndexes() {
        return indexes.stream().filter(i -> !i.isPrimaryKey()).collect(Collectors.toList());
    }

    /** A foreign key constraint. */
    @Getter
    @Setter
    public static class ForeignKeyMeta {
        private String name;
        private String columnName;
        private String referencedTable;
        private String referencedColumn;
        private String updateRule;
        private String deleteRule;
    }
}
