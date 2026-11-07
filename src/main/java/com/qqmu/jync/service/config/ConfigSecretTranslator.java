package com.qqmu.jync.service.config;

import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import com.qqmu.jync.util.CryptoUtil;
import com.qqmu.jync.util.DeluxTextEncryptor;

/**
 * Re-keys one stored secret from the exporting deployment's crypto key to this one's.
 *
 * <p>Deliberately not {@link CryptoUtil#reencrypt}: that decrypts with the <em>current</em> key,
 * which is the target's, while a package's {@code enc:} values belong to the source. Here the
 * source key is supplied by the package's {@code meta.sourceCrypto}, and the plaintext is encrypted
 * again with the live {@link CryptoUtil}.
 *
 * <p>A value the source key cannot read aborts the import: writing it through unchanged would leave
 * a row that fails on every later use, and dropping it would silently lose a credential.
 */
@Component
public class ConfigSecretTranslator {

    private final CryptoUtil crypto;

    public ConfigSecretTranslator(CryptoUtil crypto) {
        this.crypto = crypto;
    }

    public String toTargetKey(String stored, String sourcePassword, String sourceSalt) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!stored.startsWith(CryptoUtil.PREFIX)) {
            // Written before this deployment encrypted its secrets.
            return crypto.encrypt(stored);
        }
        TextEncryptor source =
                DeluxTextEncryptor.create(nullToEmpty(sourcePassword), nullToEmpty(sourceSalt));
        String plain;
        try {
            plain = source.decrypt(stored.substring(CryptoUtil.PREFIX.length()));
        } catch (RuntimeException e) {
            // The exception message from the encryptor may quote the ciphertext; keep only the key.
            throw new ConfigPackageException("error.config.import.secretUnreadable");
        }
        return crypto.encrypt(plain);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
