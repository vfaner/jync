package com.qqmu.jync.service.alert;

import java.util.List;

/**
 * Reads the application's existing state and reports the faults the alert rules can see.
 *
 * <p>Read-only by contract: collecting must not start, stop or retry anything. The evaluator
 * owns the decision to notify, which keeps the checks testable without a webhook.
 */
public interface AlertSignalCollector {

    List<AlertEvent> collect();
}
