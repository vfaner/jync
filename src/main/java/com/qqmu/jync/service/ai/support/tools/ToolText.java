package com.qqmu.jync.service.ai.support.tools;

import com.qqmu.jync.service.diagnostics.DiagnosticRedactor;

/**
 * Free text leaving a support tool for the external model.
 *
 * <p>Tool results are sent verbatim to a third-party provider. A driver error or a change-log
 * detail can carry a {@code jdbc:} URL with credentials, a bearer token or a password
 * assignment; {@link DiagnosticRedactor} already masks those for the diagnostics bundle, and
 * the same text must pass through it here. Masking runs before the cap, so a value that would
 * have been truncated away is already replaced by {@code <redacted>}; cutting a marker in half
 * is harmless.
 */
final class ToolText {

    /** docs/security-data-boundary.md promises error summaries of at most 200 characters. */
    static final int MAX_SUMMARY = 200;

    private ToolText() {
    }

    static String safe(String text, int max) {
        if (text == null) {
            return null;
        }
        String redacted = DiagnosticRedactor.redactText(text);
        return redacted.length() > max ? redacted.substring(0, max) : redacted;
    }
}
