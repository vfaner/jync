package com.qqmu.jync.service.etl;

/**
 * A source-side failure that has no {@link java.sql.SQLException}. The runner attributes it to
 * {@code Side.SOURCE}; the precheck turns {@link #messageKey()} and {@link #detail()} into a check.
 *
 * <p>{@link #getMessage()} returns the detail when there is one, because the run history shows
 * the message to an operator; the key alone would be unreadable there.
 */
public final class EtlSourceException extends RuntimeException {

    private final String messageKey;
    private final String detail;

    public EtlSourceException(String messageKey) {
        this(messageKey, null, null);
    }

    public EtlSourceException(String messageKey, String detail) {
        this(messageKey, detail, null);
    }

    public EtlSourceException(String messageKey, String detail, Throwable cause) {
        super(detail == null || detail.isBlank() ? messageKey : detail, cause);
        this.messageKey = messageKey;
        this.detail = detail;
    }

    public String messageKey() {
        return messageKey;
    }

    public String detail() {
        return detail;
    }
}
