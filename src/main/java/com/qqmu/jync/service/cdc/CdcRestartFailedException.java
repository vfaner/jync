package com.qqmu.jync.service.cdc;

/**
 * A rebaseline whose destructive half committed but whose restart failed.
 *
 * <p>By the time the consumer is started, the old position row is gone, the cursors are
 * cleared and the ERROR hat has been lifted — there is nothing to roll back. Reporting that
 * as a generic 500 told the caller less than nothing: the reset did succeed, and retrying
 * (or letting supervision start the consumer) is the actual next step. The fixed key is
 * what the page localizes; the cause stays in the log.
 */
public class CdcRestartFailedException extends RuntimeException {

    public static final String MESSAGE_KEY = "error.cdc.rebaseline.restartFailed";

    public CdcRestartFailedException(Throwable cause) {
        super(MESSAGE_KEY, cause);
    }
}
