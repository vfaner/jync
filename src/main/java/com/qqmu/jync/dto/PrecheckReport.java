package com.qqmu.jync.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of a read-only sync precheck.
 *
 * <p>Severities are about what a future cycle will meet, not about whether the API call
 * succeeded: an {@link Severity#ERROR} check is still a normal response body.
 */
public record PrecheckReport(Long projectId, String projectName, Instant checkedAt,
                             long elapsedMs, List<Check> checks) {

    public enum Severity {
        /** Nothing to do for this check. */
        OK,
        /** A cycle will cope, but the operator should know. */
        WARN,
        /** A cycle would fail or write something unsafe. */
        ERROR,
        /** The check does not apply to this project or product. */
        SKIP
    }

    /**
     * One check outcome.
     *
     * <p>{@code messageKey} is an i18n key; {@code detail} carries the dynamic names and
     * reasons. Change-event payloads are deliberately not copied into either.
     */
    public record Check(String code, Severity severity, String objectType, String objectName,
                        String messageKey, String detail) {
    }

    /** True when no check reports an ERROR. WARN/SKIP do not fail the precheck. */
    @JsonProperty("passed")
    public boolean passed() {
        return checks.stream().noneMatch(check -> check.severity() == Severity.ERROR);
    }

    public long count(Severity severity) {
        return checks.stream().filter(check -> check.severity() == severity).count();
    }
}
