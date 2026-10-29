package com.qqmu.jync.service.cdc;

/**
 * A YStream condition no reconnect can fix: the transaction carries chunked LOB rows Jync
 * cannot assemble, the instance's 32-service budget is spent, a selected table has no
 * table-level supplemental logging, the stored resume position has fallen outside the
 * retained log, or the service was replaced outside Jync.
 *
 * <p>Carries a fixed marker so {@link CdcFailureClassifier} can park the project in ERROR
 * instead of backing off forever on the same wall ({@code Kind.YSTREAM_BLOCKED}). Every
 * message names the remedy, because the panel shows the text verbatim.
 */
public final class YStreamFatalException extends RuntimeException {

    /** Prefix the failure classifier matches; keep it stable — it is the contract. */
    static final String MARKER = "YStream CDC blocked";

    private YStreamFatalException(String detail, Throwable cause) {
        super(MARKER + ": " + detail, cause);
    }

    /** A condition the source's own state has to fix (grants, logging, service budget). */
    public static YStreamFatalException blocked(String detail) {
        return new YStreamFatalException(detail, null);
    }

    /** A blocked condition discovered while handling another failure. */
    public static YStreamFatalException blocked(String detail, Throwable cause) {
        return new YStreamFatalException(detail, cause);
    }
}
