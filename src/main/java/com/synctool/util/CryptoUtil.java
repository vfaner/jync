package com.synctool.util;

import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import com.synctool.config.SyncProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Encrypts stored database passwords at rest.
 *
 * <p>Values are stored with an {@value #PREFIX} marker so an already-encrypted value is
 * never double-encrypted when an entity is re-saved without the password being edited,
 * and so plaintext left by an earlier version can still be read.
 */
@Component
@Slf4j
public class CryptoUtil {

    private static final String PREFIX = "enc:";

    private final TextEncryptor encryptor;

    public CryptoUtil(SyncProperties properties) {
        // Delux encryptor: AES-256 CBC with a random IV per value.
        this.encryptor = Encryptors.delux(properties.getCryptoPassword(), properties.getCryptoSalt());
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) {
            return plain;
        }
        return PREFIX + encryptor.encrypt(plain);
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!isEncrypted(stored)) {
            // Written before encryption was enabled, or the key changed. Use as-is.
            return stored;
        }
        try {
            return encryptor.decrypt(stored.substring(PREFIX.length()));
        } catch (Exception e) {
            log.error("Failed to decrypt stored password; the crypto key may have changed "
                    + "since it was saved. Re-enter the password for this connection.");
            throw new IllegalStateException("password.decrypt.failed", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
