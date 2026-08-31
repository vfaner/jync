package com.synctool.dto.meta;

import lombok.Getter;
import lombok.Setter;

/** A column definition, normalized across products. */
@Getter
@Setter
public class ColumnMeta {

    private String name;

    /** Product-specific type name as reported by the driver, e.g. {@code VARCHAR}. */
    private String typeName;

    /** {@link java.sql.Types} constant. */
    private int jdbcType;

    private Integer size;

    private Integer decimalDigits;

    private boolean nullable = true;

    private String defaultValue;

    private boolean autoIncrement;

    private String remarks;

    /** 1-based position in the table. */
    private int ordinalPosition;

    /** True when the column takes part in the primary key. */
    private boolean primaryKey;

    /**
     * Signature used for structural diffing. Deliberately excludes
     * {@link #ordinalPosition} and {@link #remarks} so that a comment edit or a
     * column reorder does not trigger a spurious ALTER.
     */
    public String signature() {
        return String.join("|",
                String.valueOf(name).toUpperCase(),
                String.valueOf(typeName).toUpperCase(),
                String.valueOf(size),
                String.valueOf(decimalDigits),
                String.valueOf(nullable),
                String.valueOf(defaultValue),
                String.valueOf(autoIncrement));
    }

    @Override
    public String toString() {
        return name + " " + typeName + (size != null && size > 0 ? "(" + size + ")" : "");
    }
}
