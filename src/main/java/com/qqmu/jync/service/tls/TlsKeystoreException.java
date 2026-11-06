package com.qqmu.jync.service.tls;

/**
 * A TLS failure expressed as an i18n key.
 *
 * <p>Keystore operations handle passphrases and file contents, so the message is never a
 * rendered sentence: callers flash the key and the page translates it. The cause is kept for
 * logging, but {@link #toString()} stays on the key — a JDK message may quote the alias, the
 * path, or (on some providers) part of the input, none of which belongs in a log line.
 */
public class TlsKeystoreException extends RuntimeException {

    private final String messageKey;

    public TlsKeystoreException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public TlsKeystoreException(String messageKey, Throwable cause) {
        super(messageKey, cause);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + messageKey;
    }
}
