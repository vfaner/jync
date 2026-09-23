package com.qqmu.jync.util;

import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import com.qqmu.jync.config.SyncProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Encrypts stored database passwords at rest.
 *
 * <p>Values are stored with an {@value #PREFIX} marker so an already-encrypted value is
 * never double-encrypted when an entity is re-saved without the password being edited,
 * and so plaintext left by an earlier version can still be read.
 *
 * <p>The marker alone is ambiguous — a user password may literally start with
 * {@value #PREFIX} — so "already encrypted" means "prefixed <em>and</em> decryptable by
 * this encryptor". A prefixed value that fails to decrypt (genuine plaintext, or
 * ciphertext from a different key) is encrypted like any other value, which keeps
 * encrypt/decrypt symmetric: every stored value that carries the marker decrypts to
 * exactly what the caller passed in.
 */
@Component
@Slf4j
public class CryptoUtil {

    private static final String PREFIX = "enc:";

    /** The shipped defaults; used only to warn. Kept in sync by CryptoDefaultsTest. */
    private static final String DEFAULT_CRYPTO_PASSWORD = "synctool-default-key-change-me";
    private static final String DEFAULT_CRYPTO_SALT = "5c0744940b5c369b";

    private final TextEncryptor encryptor;

    public CryptoUtil(SyncProperties properties) {
        // Delux encryptor: AES-256 CBC with a random IV per value.
        this.encryptor = Encryptors.delux(properties.getCryptoPassword(), properties.getCryptoSalt());
        if (DEFAULT_CRYPTO_PASSWORD.equals(properties.getCryptoPassword())
                || DEFAULT_CRYPTO_SALT.equals(properties.getCryptoSalt())) {
            // Same treatment as the default-login-password banner: the built-in key is public
            // source, so anything "encrypted" with it is merely obfuscated.
            log.warn("Using the built-in default crypto key/salt: stored database passwords and API "
                    + "keys are decryptable by anyone who knows the open-source defaults. Set "
                    + "sync.crypto-password and sync.crypto-salt before saving real credentials.");
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        if (isEncrypted(plain) && canDecrypt(plain)) {
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

    private boolean canDecrypt(String stored) {
        try {
            encryptor.decrypt(stored.substring(PREFIX.length()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
