package com.qqmu.jync.service.config;

/**
 * Layout constants for a {@code .jync} configuration package.
 *
 * <pre>
 *  0..3   magic  "JYNC"
 *  4      format version (this class's {@link #FORMAT_VERSION})
 *  5      flags: bit 0 = includeState
 *  6..21  salt  (PBKDF2)
 * 22..33  IV    (AES-GCM nonce)
 * 34..    AES-256-GCM ciphertext || 128-bit tag
 * </pre>
 *
 * <p>The first six bytes stay in the clear so a later version can recognise its own files without
 * a passphrase, and are fed to the cipher as additional authenticated data so the flags cannot be
 * edited in place. Everything else — including the zip's entry names and the manifest — is inside
 * the ciphertext.
 */
public final class ConfigPackageFormat {

    private ConfigPackageFormat() {
    }

    static final byte[] MAGIC = {'J', 'Y', 'N', 'C'};

    /** Bumped only when the envelope layout changes. */
    static final int FORMAT_VERSION = 1;

    /** Bumped when the JSON DTOs inside the package change incompatibly. */
    static final int SCHEMA_VERSION = 1;

    static final int SALT_BYTES = 16;
    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;
    static final int KEY_BITS = 256;

    /** PBKDF2-HMAC-SHA256 work factor; OWASP's 2023 floor for this PRF. */
    static final int KDF_ITERATIONS = 210_000;

    public static final int MIN_PASSPHRASE_CHARS = 8;
    public static final int MAX_PASSPHRASE_CHARS = 1024;

    /** Upload ceiling; the importer checks the inflated size separately. */
    static final long MAX_PACKAGE_BYTES = 20L * 1024 * 1024;
    static final long MAX_UNCOMPRESSED_BYTES = 40L * 1024 * 1024;

    static final int FLAG_INCLUDE_STATE = 1;

    /** magic + version + flags + salt + IV. */
    static final int HEADER_BYTES = MAGIC.length + 2 + SALT_BYTES + IV_BYTES;

    /** The authenticated prefix: magic, version, flags. */
    static final int AAD_BYTES = MAGIC.length + 2;
}
