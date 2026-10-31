package com.qqmu.jync.service.alert;

import java.time.Instant;

import com.qqmu.jync.model.AlertCategory;

/**
 * One fault the collector currently sees, ready to be compared against {@code alert_state}.
 *
 * <p>The detail is carried as an i18n key plus arguments rather than a rendered sentence, so
 * the evaluator renders it in the locale of the moment and tests never depend on wording.
 *
 * @param category   what kind of fault
 * @param projectId  the owning project, or 0 for a connection-level fault
 * @param subject    the project name, or the connection name for a connection-level fault
 * @param object     the table name, or empty for a project-wide condition
 * @param detailKey  i18n key describing what was observed
 * @param detailArgs arguments for {@code detailKey}
 * @param occurredAt when the observed event happened (only meaningful for event categories)
 */
public record AlertEvent(AlertCategory category, long projectId, String subject, String object,
                         String detailKey, Object[] detailArgs, Instant occurredAt) {

    public AlertEvent {
        object = object == null ? "" : object;
        detailArgs = detailArgs == null ? new Object[0] : detailArgs;
    }

    /** Stable identity across evaluations; also the {@code alert_state} uniqueness key. */
    public String dedupKey() {
        return category.name() + ":" + projectId + ":" + object;
    }
}
