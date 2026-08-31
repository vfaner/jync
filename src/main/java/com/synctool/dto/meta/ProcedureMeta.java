package com.synctool.dto.meta;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** A stored procedure or function definition. */
@Getter
@Setter
public class ProcedureMeta {

    private String name;

    private String schema;

    /** {@code PROCEDURE} or {@code FUNCTION}. */
    private String routineType = "PROCEDURE";

    /** Full body text as stored by the source product. */
    private String definition;

    /** Return type for functions; null for procedures. */
    private String returnType;

    private List<ParamMeta> parameters = new ArrayList<>();

    public boolean isFunction() {
        return "FUNCTION".equalsIgnoreCase(routineType);
    }

    public String signature() {
        return String.valueOf(name).toUpperCase() + "|" + routineType + "|"
                + ViewMeta.normalize(definition);
    }

    /** A routine parameter. */
    @Getter
    @Setter
    public static class ParamMeta {
        private String name;
        /** IN, OUT or INOUT. */
        private String mode = "IN";
        private String typeName;
        private Integer size;
        private int ordinalPosition;
    }
}
