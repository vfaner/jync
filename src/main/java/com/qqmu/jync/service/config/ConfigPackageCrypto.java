package com.qqmu.jync.service.config;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Seals a configuration package under a passphrase, and opens one again.
 *
 * <p>PBKDF2-HMAC-SHA256 derives a 256-bit key from the passphrase and the per-package salt; AES-GCM
 * then encrypts the zip and authenticates the cleartext header. A wrong passphrase and an edited
 * byte both surface as the same {@code badPassphrase} key on purpose — telling them apart would let
 * an attacker learn which half of the guess was right.
 */
@Component
public class ConfigPackageCrypto {

    /** A decrypted package plus the flag the exporter recorded in the header. */
    public record Opened(byte[] zip, boolean includeState) {
    }

    private final SecureRandom random = new SecureRandom();

    /**
     * Seals {@code zip}.
     *
     * @param passphrase at least {@value ConfigPackageFormat#MIN_PASSPHRASE_CHARS} characters;
     *                   the caller has already checked the two fields match
     */
    public byte[] encrypt(byte[] zip, char[] passphrase, boolean includeState) {
        requireUsablePassphrase(passphrase);

        byte[] salt = new byte[ConfigPackageFormat.SALT_BYTES];
        byte[] iv = new byte[ConfigPackageFormat.IV_BYTES];
        random.nextBytes(salt);
        random.nextBytes(iv);

        byte[] header = header(salt, iv, includeState);
        byte[] key = derive(passphrase, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(ConfigPackageFormat.TAG_BITS, iv));
            cipher.updateAAD(Arrays.copyOf(header, ConfigPackageFormat.AAD_BYTES));
            byte[] ciphertext = cipher.doFinal(zip);

            byte[] sealed = new byte[header.length + ciphertext.length];
            System.arraycopy(header, 0, sealed, 0, header.length);
            System.arraycopy(ciphertext, 0, sealed, header.length, ciphertext.length);
            return sealed;
        } catch (GeneralSecurityException e) {
            throw new ConfigPackageException("error.config.import.failed", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Opens a sealed package.
     *
     * <p>Magic and version are checked before the KDF runs, so a file that is not a package at all
     * — or one written by a newer Jync — costs nothing to reject and says which it is.
     */
    public Opened decrypt(byte[] sealed, char[] passphrase) {
        if (sealed == null || sealed.length < ConfigPackageFormat.HEADER_BYTES + (ConfigPackageFormat.TAG_BITS / 8)) {
            throw new ConfigPackageException("error.config.import.badFormat");
        }
        if (!Arrays.equals(Arrays.copyOfRange(sealed, 0, ConfigPackageFormat.MAGIC.length),
                ConfigPackageFormat.MAGIC)) {
            throw new ConfigPackageException("error.config.import.badFormat");
        }
        int formatVersion = sealed[ConfigPackageFormat.MAGIC.length] & 0xFF;
        if (formatVersion > ConfigPackageFormat.FORMAT_VERSION) {
            throw new ConfigPackageException("error.config.import.schemaTooNew");
        }
        if (formatVersion != ConfigPackageFormat.FORMAT_VERSION) {
            throw new ConfigPackageException("error.config.import.badFormat");
        }
        requireUsablePassphrase(passphrase);

        int flagsAt = ConfigPackageFormat.MAGIC.length + 1;
        boolean includeState =
                (sealed[flagsAt] & ConfigPackageFormat.FLAG_INCLUDE_STATE) != 0;
        byte[] salt = Arrays.copyOfRange(sealed, flagsAt + 1, flagsAt + 1 + ConfigPackageFormat.SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(sealed, flagsAt + 1 + ConfigPackageFormat.SALT_BYTES,
                ConfigPackageFormat.HEADER_BYTES);

        byte[] key = derive(passphrase, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(ConfigPackageFormat.TAG_BITS, iv));
            cipher.updateAAD(Arrays.copyOf(sealed, ConfigPackageFormat.AAD_BYTES));
            byte[] zip = cipher.doFinal(sealed, ConfigPackageFormat.HEADER_BYTES,
                    sealed.length - ConfigPackageFormat.HEADER_BYTES);
            return new Opened(zip, includeState);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException covers both a wrong key and a modified byte; nothing else in
            // this block can fail after the size and header checks above.
            throw new ConfigPackageException("error.config.import.badPassphrase", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static void requireUsablePassphrase(char[] passphrase) {
        int length = passphrase == null ? 0 : passphrase.length;
        if (length < ConfigPackageFormat.MIN_PASSPHRASE_CHARS) {
            throw new ConfigPackageException("error.config.passphrase.tooShort");
        }
        if (length > ConfigPackageFormat.MAX_PASSPHRASE_CHARS) {
            throw new ConfigPackageException("error.config.passphrase.tooLong");
        }
    }

    private static byte[] header(byte[] salt, byte[] iv, boolean includeState) {
        ByteBuffer buffer = ByteBuffer.allocate(ConfigPackageFormat.HEADER_BYTES);
        buffer.put(ConfigPackageFormat.MAGIC);
        buffer.put((byte) ConfigPackageFormat.FORMAT_VERSION);
        buffer.put((byte) (includeState ? ConfigPackageFormat.FLAG_INCLUDE_STATE : 0));
        buffer.put(salt);
        buffer.put(iv);
        return buffer.array();
    }

    private static byte[] derive(char[] passphrase, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt,
                ConfigPackageFormat.KDF_ITERATIONS, ConfigPackageFormat.KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new ConfigPackageException("error.config.import.failed", e);
        } finally {
            spec.clearPassword();
        }
    }
}
