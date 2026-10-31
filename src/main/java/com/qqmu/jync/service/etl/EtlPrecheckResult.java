package com.qqmu.jync.service.etl;

import java.util.List;

/**
 * What a precheck found, without running the load.
 *
 * <p>{@code passed} means "the run would probably get past its first row": every message is
 * either informational or a warning the operator accepts. A single {@link Level#ERROR} fails it.
 */
public record EtlPrecheckResult(List<Check> checks, List<ColumnInfo> columns) {

    public EtlPrecheckResult {
        checks = List.copyOf(checks);
        columns = List.copyOf(columns);
    }

    public boolean passed() {
        return checks.stream().noneMatch(check -> check.level() == Level.ERROR);
    }

    public enum Level {
        OK, WARN, ERROR
    }

    /**
     * One finding. {@code code} is an i18n message key so the page can localize it; {@code detail}
     * is the raw database text (or the offending name) and is shown verbatim because it is what
     * tells the operator how to fix the SQL.
     */
    public record Check(Level level, String code, String detail) {
    }

    /** One column of the extraction result, in query order. */
    public record ColumnInfo(String name, String typeName) {
    }
}
