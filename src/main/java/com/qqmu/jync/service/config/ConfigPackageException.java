package com.qqmu.jync.service.config;

/**
 * A configuration package that cannot be read, or a passphrase that cannot be used.
 *
 * <p>The message is an i18n key, never a value from the package: the key is what the page shows,
 * and the passphrase, the ciphertext and the JDBC URLs inside the package must not reach a view or
 * a log.
 */
public class ConfigPackageException extends RuntimeException {

    private final String messageKey;

    public ConfigPackageException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public ConfigPackageException(String messageKey, Throwable cause) {
        super(messageKey, cause);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
