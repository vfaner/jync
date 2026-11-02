package com.qqmu.jync.service.migration;

import java.util.List;

/** The checks shown before a task is saved or run. Any ERROR blocks execution. */
public record MigrationPrecheckResult(List<Check> checks) {

    public MigrationPrecheckResult {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public boolean passed() {
        return checks.stream().noneMatch(check -> check.level() == Level.ERROR);
    }

    public enum Level {
        OK,
        WARN,
        ERROR
    }

    public record Check(Level level, String code, String detail) {
    }
}
