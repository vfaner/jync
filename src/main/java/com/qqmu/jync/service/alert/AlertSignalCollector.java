package com.qqmu.jync.service.alert;

import java.util.List;
import java.util.Locale;

/**
 * Reads the application's existing state and reports the faults the alert rules can see.
 *
 * <p>Read-only by contract: collecting must not start, stop or retry anything. The evaluator
 * owns the decision to notify, which keeps the checks testable without a webhook.
 */
public interface AlertSignalCollector {

    /**
     * @param locale the language the resulting alert text must be written in; collection runs
     *               in a scheduler thread that has no request locale of its own
     */
    List<AlertEvent> collect(Locale locale);
}
