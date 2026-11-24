package com.qqmu.jync.service.ai.support;

import java.util.List;

/** The events an answer may emit; the controller maps them to SSE events. */
public interface SupportSink {

    void delta(String text);

    /**
     * Progress of one tool call. A round's calls are announced as {@link ToolState#QUEUED}
     * first, then each moves to {@code RUNNING} and finally {@code DONE} or {@code FAILED} —
     * the browser must never show "running" for a call that already returned.
     */
    void tool(String callId, String name, String labelKey, ToolState state);

    enum ToolState { QUEUED, RUNNING, DONE, FAILED }

    void draft(DraftView draft);

    /**
     * A prepared write waiting for the human to click. The token is single-use and bound to
     * the asking account; {@code titleKey} and {@code resourceKindKey} are i18n keys the
     * browser resolves.
     */
    void action(ActionView action);

    void error(String messageKey);

    /**
     * Same as {@link #error(String)} but carries the provider's own words (HTTP status and
     * error body) so an operator can act on it — an expired subscription or a wrong key is
     * invisible behind a generic "call failed" message.
     */
    default void error(String messageKey, String detail) {
        error(messageKey);
    }

    void done();

    record DraftView(String token, String title, String summary, List<Field> fields) {
    }

    /**
     * @param fields per-field old/new values for a parameter card; empty for a plain action
     * @param noteKey optional i18n key explaining when the change takes effect
     */
    record ActionView(String token, String titleKey, String resourceKindKey,
                      String resourceName, List<ActionField> fields, String noteKey,
                      List<String> affectedProjects) {
    }

    /**
     * One row on a parameter card. Labels are i18n keys (a table or driver-parameter name is
     * passed through as its own key and rendered verbatim by the lookup fallback); values are
     * display strings or numbers. {@code beforeIsDefault}/{@code afterIsDefault} mean "not
     * set", so the card can show the localized default instead of an empty cell.
     */
    record ActionField(String field, String labelKey, Object before, Object after,
                       boolean beforeIsDefault, boolean afterIsDefault) {
    }

    record Field(String labelKey, String value) {
    }
}
