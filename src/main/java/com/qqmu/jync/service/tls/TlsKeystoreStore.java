package com.qqmu.jync.service.tls;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import com.qqmu.jync.util.CryptoUtil;

/**
 * The on-disk home of the app-terminated TLS certificate.
 *
 * <p>Files rather than rows: the HTTPS connector is created during
 * {@code ServletWebServerApplicationContext.onRefresh()}, before the DataSource exists, so a
 * certificate in the metadata database would not be readable at the moment it is needed.
 *
 * <p>The sidecar {@code tls.properties} is the commit point. A save writes the keystore
 * generation first and then atomically replaces the sidecar; a crash before that move leaves the
 * old sidecar pointing at the old generation, which orphan cleanup removes on the next save.
 * Nothing here reads the keystore's contents — that is {@link TlsCertificateInspector}'s job.
 *
 * <p>The passphrase is stored through {@link CryptoUtil}, so the sidecar is only as secret as the
 * installation's crypto key. {@link #reencryptPassphrases(TextEncryptor)} carries it through a
 * crypto-key rotation; until the next save both ciphertexts are kept, and reads try each.
 */
@Component
public class TlsKeystoreStore {

    static final String SIDECAR = "tls.properties";
    static final int VERSION = 1;
    static final String KEYSTORE_PREFIX = "keystore-";
    static final String KEY_PASSPHRASE = "passphrase";
    static final String KEY_PASSPHRASE_NEXT = "passphrase.next";

    private static final Logger log = LoggerFactory.getLogger(TlsKeystoreStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> PRIVATE_DIR =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            PosixFilePermissions.fromString("rw-------");

    private final Path directory;
    private final CryptoUtil crypto;

    public TlsKeystoreStore(@Value("${app.tls.directory:./data/tls}") String directory,
                            CryptoUtil crypto) {
        this.directory = Paths.get(directory).toAbsolutePath().normalize();
        this.crypto = crypto;
    }

    /** Page reads: a broken configuration becomes a key, never an exception. */
    public TlsStoreSnapshot snapshot() {
        try {
            return read();
        } catch (TlsKeystoreException e) {
            return new TlsStoreSnapshot(java.util.Optional.empty(), e.messageKey(),
                    Files.isRegularFile(sidecar()), anyKeystorePresent());
        }
    }

    /** Absolute location of a generation, for the connector's keystore attribute. */
    public Path keystorePath(TlsConfig config) {
        return resolveContained(config.file());
    }

    /** The bytes of a generation that {@link #requireConfig()} already validated. */
    public byte[] keystoreBytes(TlsConfig config) {
        try {
            return Files.readAllBytes(keystorePath(config));
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.keystoreMissing", e);
        }
    }

    /** Startup reads: any problem must stop the boot rather than serve an unexpected certificate. */
    public TlsConfig requireConfig() {
        TlsStoreSnapshot snapshot = read();
        if (snapshot.problemKey() != null) {
            throw new TlsKeystoreException(snapshot.problemKey());
        }
        return snapshot.config()
                .orElseThrow(() -> new TlsKeystoreException("error.tls.notConfigured"));
    }

    public synchronized TlsConfig save(byte[] keystore, TlsKeystoreFormat format,
                                       char[] passphrase, int httpsPort, boolean redirectHttp) {
        try {
            createDirectory();
            String file = generationName(format);
            Path target = directory.resolve(file);
            writePrivate(target, keystore);

            TlsConfig config = new TlsConfig(format, file,
                    passphrase == null ? "" : new String(passphrase),
                    httpsPort, redirectHttp, Instant.now(), sha256Hex(keystore));
            writeSidecar(config);
            cleanupOrphans(file);
            return config;
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.saveFailed", e);
        }
    }

    /** Removes the sidecar and every generation; idempotent. */
    public synchronized boolean delete() {
        boolean present = Files.isRegularFile(sidecar()) || anyKeystorePresent();
        if (!present) {
            return false;
        }
        try {
            Files.deleteIfExists(sidecar());
            removeKeystores(null);
            return true;
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.deleteFailed", e);
        }
    }

    /**
     * Re-wraps the saved passphrase under a new crypto key, without touching the keystore.
     *
     * <p>The old ciphertext stays in {@code passphrase}; the new one goes to
     * {@code passphrase.next}. A reader can therefore still open the file with either key until
     * the next save writes it under the key that is current then.
     */
    public synchronized void reencryptPassphrases(TextEncryptor target) {
        Path sidecar = sidecar();
        if (!Files.isRegularFile(sidecar)) {
            return;
        }
        Properties properties;
        try {
            properties = loadProperties(sidecar);
        } catch (TlsKeystoreException e) {
            log.warn("Cannot re-encrypt the saved TLS passphrase: {}. The next start will fail "
                    + "unless the certificate is uploaded again.", e.messageKey());
            return;
        }
        String plain = decryptPassphraseOrNull(properties);
        if (plain == null) {
            log.warn("The saved TLS passphrase cannot be decrypted with the current crypto key; "
                    + "the next start will fail unless the certificate is uploaded again.");
            return;
        }
        properties.setProperty(KEY_PASSPHRASE_NEXT, CryptoUtil.PREFIX + target.encrypt(plain));
        try {
            writePropertiesAtomic(properties);
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.saveFailed", e);
        }
    }

    private TlsStoreSnapshot read() {
        Path sidecar = sidecar();
        if (!Files.isRegularFile(sidecar)) {
            return TlsStoreSnapshot.empty();
        }
        Properties properties = loadProperties(sidecar);

        int version;
        try {
            version = Integer.parseInt(properties.getProperty("version", ""));
        } catch (NumberFormatException e) {
            throw new TlsKeystoreException("error.tls.storeCorrupt", e);
        }
        if (version > VERSION) {
            throw new TlsKeystoreException("error.tls.storeUnsupportedVersion");
        }
        if (version != VERSION) {
            throw new TlsKeystoreException("error.tls.storeCorrupt");
        }

        TlsKeystoreFormat format;
        try {
            format = TlsKeystoreFormat.valueOf(properties.getProperty("format", ""));
        } catch (IllegalArgumentException e) {
            throw new TlsKeystoreException("error.tls.storeCorrupt", e);
        }

        String file = properties.getProperty("file");
        Path keystore = resolveContained(file);
        if (!Files.isRegularFile(keystore)) {
            throw new TlsKeystoreException("error.tls.keystoreMissing");
        }
        if (!sha256Hex(keystore).equalsIgnoreCase(properties.getProperty("sha256", ""))) {
            throw new TlsKeystoreException("error.tls.keystoreChanged");
        }

        String passphrase = decryptPassphrase(properties);

        int httpsPort;
        try {
            httpsPort = Integer.parseInt(properties.getProperty("httpsPort", ""));
        } catch (NumberFormatException e) {
            throw new TlsKeystoreException("error.tls.storeCorrupt", e);
        }
        if (httpsPort < 1 || httpsPort > 65535) {
            throw new TlsKeystoreException("error.tls.portInvalid");
        }

        Instant updatedAt;
        try {
            updatedAt = Instant.parse(properties.getProperty("updatedAt", ""));
        } catch (DateTimeParseException e) {
            throw new TlsKeystoreException("error.tls.storeCorrupt", e);
        }

        boolean redirectHttp = Boolean.parseBoolean(properties.getProperty("redirectHttp", "false"));
        String sha256 = properties.getProperty("sha256", "").toLowerCase(Locale.ROOT);

        return new TlsStoreSnapshot(java.util.Optional.of(new TlsConfig(format, file, passphrase,
                httpsPort, redirectHttp, updatedAt, sha256)), null, true, true);
    }

    private Properties loadProperties(Path sidecar) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(sidecar, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            throw new TlsKeystoreException("error.tls.storeCorrupt", e);
        }
        return properties;
    }

    private Path resolveContained(String file) {
        if (file == null || file.isBlank()
                || file.contains("/") || file.contains("\\")
                || file.equals(".") || file.equals("..")) {
            throw new TlsKeystoreException("error.tls.keystorePath");
        }
        Path resolved = directory.resolve(file).normalize();
        if (!directory.equals(resolved.getParent())) {
            throw new TlsKeystoreException("error.tls.keystorePath");
        }
        return resolved;
    }

    private String decryptPassphrase(Properties properties) {
        String plain = decryptPassphraseOrNull(properties);
        if (plain == null) {
            throw new TlsKeystoreException("error.tls.passphraseUndecryptable");
        }
        return plain;
    }

    private String decryptPassphraseOrNull(Properties properties) {
        for (String key : List.of(KEY_PASSPHRASE, KEY_PASSPHRASE_NEXT)) {
            String stored = properties.getProperty(key);
            if (stored == null || stored.isEmpty()) {
                continue;
            }
            try {
                return crypto.decrypt(stored);
            } catch (RuntimeException ignored) {
                // Try the next ciphertext: after a key rotation the older one belongs to the
                // previous key. Neither opening is a normal state and is reported below.
            }
        }
        return null;
    }

    private void writeSidecar(TlsConfig config) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", String.valueOf(VERSION));
        properties.setProperty("format", config.format().name());
        properties.setProperty("file", config.file());
        properties.setProperty(KEY_PASSPHRASE, crypto.encrypt(config.passphrase()));
        properties.setProperty("httpsPort", String.valueOf(config.httpsPort()));
        properties.setProperty("redirectHttp", String.valueOf(config.redirectHttp()));
        properties.setProperty("updatedAt", config.updatedAt().toString());
        properties.setProperty("sha256", config.sha256());
        writePropertiesAtomic(properties);
    }

    private void writePropertiesAtomic(Properties properties) throws IOException {
        Path temp = Files.createTempFile(directory, ".tls-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                // Written by hand rather than Properties.store: that method escapes ':' in
                // values, turning "enc:..." into "enc\:..." on disk. Reading is unaffected, but
                // the sidecar is meant to be inspectable, and grep/backup scripts look for the
                // plain marker.
                writer.write("# Jync TLS certificate; generated by the web UI. Do not edit.\n");
                List<String> names = new java.util.ArrayList<>(properties.stringPropertyNames());
                java.util.Collections.sort(names);
                for (String name : names) {
                    writer.write(name);
                    writer.write('=');
                    writer.write(properties.getProperty(name));
                    writer.write('\n');
                }
            }
            setPermissions(temp, PRIVATE_FILE);
            try {
                Files.move(temp, sidecar(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, sidecar(), StandardCopyOption.REPLACE_EXISTING);
            }
            setPermissions(sidecar(), PRIVATE_FILE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void createDirectory() throws IOException {
        Files.createDirectories(directory);
        setPermissions(directory, PRIVATE_DIR);
    }

    private void writePrivate(Path target, byte[] bytes) throws IOException {
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        setPermissions(target, PRIVATE_FILE);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException e) {
            log.warn("The filesystem under {} has no POSIX permissions; relying on the volume "
                    + "or ACL to protect the TLS directory.", path);
        } catch (IOException e) {
            log.warn("Could not restrict permissions on {}: {}", path, e.toString());
        }
    }

    private void cleanupOrphans(String keep) {
        try {
            removeKeystores(keep);
        } catch (IOException e) {
            log.warn("Could not remove an older TLS keystore generation: {}", e.toString());
        }
    }

    private void removeKeystores(String keep) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path path : stream.collect(Collectors.toList())) {
                String name = path.getFileName().toString();
                if (name.startsWith(KEYSTORE_PREFIX) && !name.equals(keep)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private boolean anyKeystorePresent() {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.anyMatch(path ->
                    path.getFileName().toString().startsWith(KEYSTORE_PREFIX));
        } catch (IOException e) {
            return false;
        }
    }

    private Path sidecar() {
        return directory.resolve(SIDECAR);
    }

    private static String generationName(TlsKeystoreFormat format) {
        byte[] random = new byte[4];
        RANDOM.nextBytes(random);
        StringBuilder hex = new StringBuilder();
        for (byte b : random) {
            hex.append(String.format("%02x", b));
        }
        return KEYSTORE_PREFIX + System.currentTimeMillis() + "-" + hex + "." + format.extension();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE", e);
        }
    }

    private static String sha256Hex(Path path) {
        try {
            return sha256Hex(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.keystoreMissing", e);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
