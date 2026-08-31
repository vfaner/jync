package com.synctool.dto.meta;

import lombok.Getter;
import lombok.Setter;

/** A view definition. */
@Getter
@Setter
public class ViewMeta {

    private String name;

    private String schema;

    /** The SELECT body, without any {@code CREATE VIEW} prefix. */
    private String definition;

    public String signature() {
        return String.valueOf(name).toUpperCase() + "|" + normalize(definition);
    }

    /** Collapses whitespace so cosmetic reformatting is not seen as a change. */
    static String normalize(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim().toUpperCase();
    }
}
