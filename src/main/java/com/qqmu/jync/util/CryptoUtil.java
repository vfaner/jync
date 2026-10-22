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

    /** Marks a stored value as ciphertext, so it is not encrypted twice on re-save. */
    public static final String PREFIX = "enc:";

    /** The shipped defaults; used only to warn. Kept in sync by CryptoDefaultsTest. */
    private static final String DEFAULT_CRYPTO_PASSWORD = "synctool-default-key-change-me";
    private static final String DEFAULT_CRYPTO_SALT = "5c0744940b5c369b";

    private volatile TextEncryptor encryptor;

    /** True while the encryptor is still the publicly shipped default. */
    private volatile boolean usingDefaultKey;

    public CryptoUtil(SyncProperties properties) {
        applyKey(properties.getCryptoPassword(), properties.getCryptoSalt());
        if (usingDefaultKey) {
            // Same treatment as the default-login-password banner: the built-in key is public
            // source, so anything "encrypted" with it is merely obfuscated.
            log.warn("Using the built-in default crypto key/salt: stored database passwords and API "
                    + "keys are decryptable by anyone who knows the open-source defaults. Set "
                    + "sync.crypto-password and sync.crypto-salt before saving real credentials.");
        }
    }

    private void applyKey(String password, String salt) {
        // Delux encryptor: AES-256 CBC with a random IV per value.
        this.encryptor = Encryptors.delux(password, salt);
        this.usingDefaultKey = DEFAULT_CRYPTO_PASSWORD.equals(password)
                || DEFAULT_CRYPTO_SALT.equals(salt);
    }

    /** Whether stored secrets still rely on the publicly known built-in key. */
    public boolean isUsingDefaultKey() {
        return usingDefaultKey;
    }

    /**
     * Switches every later operation to a new key.
     *
     * <p>Callers must have already re-encrypted the stored values: old ciphertext is not
     * readable by the new key.
     */
    public synchronized void rotateTo(String password, String salt) {
        applyKey(password, salt);
        if (!usingDefaultKey) {
            log.info("Stored database passwords and API keys are now encrypted with a key "
                    + "generated for this installation.");
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        TextEncryptor current = encryptor;
        if (isEncrypted(plain) && canDecrypt(plain, current)) {
            return plain;
        }
        return PREFIX + current.encrypt(plain);
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

    /**
     * Re-encrypts a stored value under {@code target}, keeping its plaintext.
     *
     * <p>Used by the first-boot key migration. An unreadable value aborts instead of being
     * replaced: overwriting it would destroy the only copy of the ciphertext.
     */
    public String reencrypt(String stored, TextEncryptor target) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!isEncrypted(stored)) {
            // Plaintext written before encryption was enabled.
            return PREFIX + target.encrypt(stored);
        }
        String plain;
        try {
            plain = encryptor.decrypt(stored.substring(PREFIX.length()));
        } catch (Exception e) {
            log.error("Failed to migrate a stored password; the crypto key does not match "
                    + "the ciphertext. Re-enter the password for this connection.");
            throw new IllegalStateException("password.decrypt.failed", e);
        }
        return PREFIX + target.encrypt(plain);
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static boolean canDecrypt(String stored, TextEncryptor encryptor) {
        try {
            encryptor.decrypt(stored.substring(PREFIX.length()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
