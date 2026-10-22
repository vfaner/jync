package com.qqmu.jync.config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;

/**
 * Resolves the crypto password/salt pair for this installation.
 *
 * <p>The shipped defaults ({@link SyncProperties#DEFAULT_CRYPTO_PASSWORD} /
 * {@link SyncProperties#DEFAULT_CRYPTO_SALT}) are public — they are printed in the
 * open-source jar — so anything encrypted with them is only obfuscated. On a <em>fresh</em>
 * install this resolver generates a strong secret and persists it to a {@code <store>.key}
 * file next to the database (e.g. {@code data/jync.key}), so a new deployment is actually
 * protected without any operator step. The compat boundaries that make that safe:
 *
 * <ul>
 *   <li>An explicit operator override always wins and never writes a file.</li>
 *   <li>An existing database — including an un-migrated {@code synctool.mv.db} whose stored
 *       ciphertext was produced with the shipped defaults — boots on those defaults, because a
 *       generated key cannot decrypt what an earlier version stored. {@link
 *       LegacyCryptoKeyMigration} then re-encrypts the rows and promotes a generated key.</li>
 *   <li>The key file, once present, is the source of truth and is loaded verbatim.</li>
 * </ul>
 */
public final class CryptoKeyResolver {

    /** Sidecar holding the generated password/salt for fresh installs (e.g. {@code jync.key}). */
    static final String KEY_FILE_NAME = ".key";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path storeBase;

    private CryptoKeyResolver(Path storeBase) {
        this.storeBase = storeBase;
    }

    /** Resolver for the store addressed by an H2 file base path (no URL/settings). */
    public static CryptoKeyResolver forStoreBase(Path storeBase) {
        return new CryptoKeyResolver(storeBase);
    }

    /**
     * Where the resolved secret came from, so callers can log and banner accordingly.
     */
    public enum Source {
        /** Operator set one or both halves in configuration; file untouched. */
        EXPLICIT,
        /** Existing key file was loaded verbatim. */
        KEY_FILE,
        /** Fresh install: a strong secret was generated and persisted. */
        GENERATED,
        /** Existing database: shipped defaults retained for ciphertext compatibility. */
        LEGACY_DEFAULT
    }

    /** A resolved password/salt pair plus its provenance. */
    public record Secrets(String password, String salt, Source source) {
    }

    /**
     * Resolves the pair given the configured (possibly-default) values.
     *
     * @param configuredPassword the bound {@code sync.crypto-password}
     * @param configuredSalt     the bound {@code sync.crypto-salt}
     */
    public Secrets resolve(String configuredPassword, String configuredSalt) {
        if (!isDefault(configuredPassword) || !isDefault(configuredSalt)) {
            return new Secrets(configuredPassword, configuredSalt, Source.EXPLICIT);
        }

        Path keyFile = keyFile();
        if (keyFileExists()) {
            Loaded loaded = loadFrom(keyFile);
            return new Secrets(loaded.password(), loaded.salt(), Source.KEY_FILE);
        }

        if (storeExists()) {
            // Existing (or legacy, un-migrated) data: keep the shipped defaults. A generated
            // key cannot decrypt ciphertext written by the default key.
            return new Secrets(SyncProperties.DEFAULT_CRYPTO_PASSWORD,
                    SyncProperties.DEFAULT_CRYPTO_SALT, Source.LEGACY_DEFAULT);
        }

        String password = randomHex(64);
        String salt = randomHex(16);
        persist(keyFile, password, salt);
        return new Secrets(password, salt, Source.GENERATED);
    }

    private boolean isDefault(String value) {
        return value == null
                || value.isBlank()
                || SyncProperties.DEFAULT_CRYPTO_PASSWORD.equals(value)
                || SyncProperties.DEFAULT_CRYPTO_SALT.equals(value);
    }

    /** Whether an H2 store file (current or pre-rename) already exists for this base. */
    public boolean storeExists() {
        Path base = storeBase.toAbsolutePath();
        Path dir = base.getParent();
        if (dir == null) {
            return false;
        }
        String basename = base.getFileName().toString();
        return Files.exists(dir.resolve(basename + ".mv.db"))
                || Files.exists(dir.resolve(LegacyStoreMigration.LEGACY_BASENAME + ".mv.db"));
    }

    /** Whether a usable persisted key is already in place. */
    public boolean keyFileExists() {
        // A directory at this path is not a key; treating it as "already done" would silently
        // strand the installation on the public default.
        return Files.isRegularFile(keyFile());
    }

    /**
     * Whether an interrupted migration left its key behind.
     *
     * <p>The pending file is promoted only after the re-encrypted rows commit, so its presence
     * at boot means either "database already migrated, promote" or "database not migrated,
     * redo with this key" (see {@link LegacyCryptoKeyMigration}).
     */
    public boolean pendingKeyFileExists() {
        return Files.exists(pendingKeyFile());
    }

    /** Reads the live key file; used when it appeared after the environment was resolved. */
    public Secrets readKeyFile() {
        Loaded loaded = loadFrom(keyFile());
        return new Secrets(loaded.password(), loaded.salt(), Source.KEY_FILE);
    }

    /** Reads the pending key; it must be complete. */
    public Secrets readPending() {
        Loaded loaded = loadFrom(pendingKeyFile());
        return new Secrets(loaded.password(), loaded.salt(), Source.GENERATED);
    }

    /** Generates a strong key and writes it as the pending file. */
    public Secrets generatePending() {
        String password = randomHex(64);
        String salt = randomHex(16);
        persist(pendingKeyFile(), password, salt);
        return new Secrets(password, salt, Source.GENERATED);
    }

    /** Promotes the pending key to the live one, atomically where the filesystem allows. */
    public void promotePending() {
        try {
            try {
                Files.move(pendingKeyFile(), keyFile(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pendingKeyFile(), keyFile(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not promote the pending crypto key to "
                    + keyFile() + "; stored passwords would become unreadable on the next "
                    + "boot. Ensure the data directory is writable.", e);
        }
    }

    /** Removes the pending key after a failed migration. Best effort. */
    public void discardPending() {
        try {
            Files.deleteIfExists(pendingKeyFile());
        } catch (IOException ignored) {
            // A leftover pending file is handled at the next boot (promote or redo).
        }
    }

    /** Lock file serialising the migration across processes sharing the store. */
    public Path lockFile() {
        return keyFile().resolveSibling(keyFile().getFileName() + ".lock");
    }

    Path keyFile() {
        Path base = storeBase.toAbsolutePath();
        String name = base.getFileName().toString() + KEY_FILE_NAME;
        Path dir = base.getParent();
        return (dir == null ? base : dir).resolve(name);
    }

    Path pendingKeyFile() {
        Path keyFile = keyFile();
        return keyFile.resolveSibling(keyFile.getFileName() + ".pending");
    }

    private record Loaded(String password, String salt) {
    }

    private Loaded loadFrom(Path keyFile) {
        Properties props = new Properties();
        try (var in = Files.newBufferedReader(keyFile, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read the persisted crypto key at " + keyFile
                            + "; stored passwords would be unrecoverable. Restore the file "
                            + "or re-create the connection credentials.", e);
        }
        String password = props.getProperty("password");
        String salt = props.getProperty("salt");
        if (password == null || password.isBlank() || salt == null || salt.isBlank()) {
            throw new IllegalStateException("The persisted crypto key at " + keyFile
                    + " is incomplete; refusing to start with a half-key. Restore the file "
                    + "or re-create the connection credentials.");
        }
        return new Loaded(password, salt);
    }

    private void persist(Path keyFile, String password, String salt) {
        try {
            Path dir = keyFile.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Properties props = new Properties();
            props.setProperty("password", password);
            props.setProperty("salt", salt);
            try (OutputStream out = Files.newOutputStream(keyFile)) {
                props.store(out, "Jync first-boot crypto key — do not share");
            }
            // Owner-only so the secret is not world-readable on multi-user hosts. Best effort:
            // a filesystem without POSIX permissions simply keeps its own defaults.
            try {
                Files.setPosixFilePermissions(keyFile, java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Not a POSIX filesystem (e.g. Windows): nothing more to do.
            }
        } catch (IOException e) {
            // Failing to persist means the next boot would regenerate a different key and
            // orphan everything encrypted with this one — worse than aborting now.
            throw new IllegalStateException("Could not persist the generated crypto key to "
                    + keyFile + ". Ensure the data directory is writable.", e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
