package com.qqmu.jync.service.tls;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
import java.util.Arrays;
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
    /**
     * The only names this store generates. Cleanup must match this shape, not just the prefix:
     * a path-mode keystore such as {@code keystore-prod.p12} may live in the same directory and
     * belongs to the operator — deleting it would destroy the configured certificate.
     */
    private static final java.util.regex.Pattern GENERATED_KEYSTORE =
            java.util.regex.Pattern.compile(
                    "^" + KEYSTORE_PREFIX + "\\d+-[0-9a-f]{8}\\.(p12|jks)$");
    /** Where the page's upload button stages a file before the path save records it. */
    static final String UPLOAD_DIR = "uploads";
    /**
     * How long an unreferenced staged file is kept before a save may delete it. Long enough for
     * a form left open in another tab; short enough that abandoned uploads do not accumulate.
     */
    static final java.time.Duration STAGING_RETENTION = java.time.Duration.ofHours(1);
    static final String KEY_PASSPHRASE = "passphrase";
    static final String KEY_PASSPHRASE_NEXT = "passphrase.next";
    static final String KEY_SOURCE = "source";
    static final String KEY_KEYSTORE_PATH = "keystorePath";
    static final String KEY_PEM_CERT_PATH = "pemCertPath";
    static final String KEY_PEM_KEY_PATH = "pemKeyPath";
    static final String SOURCE_UPLOAD = "upload";
    static final String SOURCE_KEYSTORE_PATH = "keystore-path";
    static final String SOURCE_PEM_PATH = "pem-path";

    /** Upper bound for a page-entered path source; the same ceiling as an upload. */
    public static final int MAX_SOURCE_BYTES = 5 * 1024 * 1024;

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
                    Files.isRegularFile(sidecar()), anyKeystorePresent(), null);
        }
    }

    /**
     * Absolute location of a generation, for the connector's keystore attribute.
     *
     * <p>Managed generations are simple file names inside {@link #directory}. A configured
     * source ({@code app.tls.keystore-path}) names a file the operator mounted anywhere, so an
     * absolute path is used as-is; relative names still may not escape the managed directory.
     */
    public Path keystorePath(TlsConfig config) {
        Path path = Paths.get(config.file());
        if (path.isAbsolute()) {
            return path;
        }
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
            writeSidecar(config, SOURCE_UPLOAD, null, null, null);
            cleanupOrphans(file);
            return config;
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.saveFailed", e);
        }
    }

    /**
     * Remembers a keystore that stays where the operator put it.
     *
     * <p>Nothing is copied: the sidecar names the absolute path and holds the encrypted
     * passphrase. A container recreation keeps the configuration as long as the data directory is
     * persistent, and a renewed file at the same path is picked up at the next read.
     *
     * @param bytes the bytes already validated by the caller, used for the digest
     */
    public synchronized TlsConfig saveKeystorePath(Path keystore, byte[] bytes,
                                                   TlsKeystoreFormat format, char[] passphrase,
                                                   int httpsPort, boolean redirectHttp) {
        try {
            createDirectory();
            Path absolute = keystore.toAbsolutePath().normalize();
            TlsConfig config = new TlsConfig(format, absolute.toString(),
                    passphrase == null ? "" : new String(passphrase),
                    httpsPort, redirectHttp, Instant.now(), sha256Hex(bytes));
            writeSidecar(config, SOURCE_KEYSTORE_PATH, absolute.toString(), null, null);
            // Generations from an earlier upload or PEM path are no longer referenced.
            cleanupOrphans(null);
            // Staged by the page's upload button and not referenced by this save: stale.
            pruneUploads(absolute);
            return config;
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.saveFailed", e);
        }
    }

    /**
     * Remembers a PEM pair kept on the server and stores the PKCS#12 derived from it.
     *
     * <p>The sidecar names both source files and the digest of their bytes. The derived keystore
     * is a managed generation so Tomcat has something to open; a later read that sees different
     * source bytes rebuilds it. The private key itself never reaches the sidecar.
     */
    public synchronized TlsConfig savePemPath(Path certificate, Path privateKey,
                                              byte[] certificatePem, byte[] privateKeyPem,
                                              byte[] pkcs12, char[] passphrase, int httpsPort,
                                              boolean redirectHttp) {
        try {
            createDirectory();
            String file = generationName(TlsKeystoreFormat.PKCS12);
            writePrivate(directory.resolve(file), pkcs12);
            Path absoluteCertificate = certificate.toAbsolutePath().normalize();
            Path absoluteKey = privateKey == null ? null : privateKey.toAbsolutePath().normalize();
            TlsConfig config = new TlsConfig(TlsKeystoreFormat.PKCS12, file,
                    passphrase == null ? "" : new String(passphrase),
                    httpsPort, redirectHttp, Instant.now(),
                    sourceDigest(certificatePem, privateKeyPem));
            writeSidecar(config, SOURCE_PEM_PATH, null, absoluteCertificate.toString(),
                    absoluteKey == null ? null : absoluteKey.toString());
            cleanupOrphans(file);
            pruneUploads(absoluteCertificate, absoluteKey);
            return config;
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.saveFailed", e);
        }
    }

    /**
     * Stores an uploaded file under {@code uploads/} and returns where it landed.
     *
     * <p>The page's file fields are path inputs with an upload button: the button calls this,
     * then fills the input with the returned absolute path, so uploads and hand-typed paths share
     * one save path and one renewal story (replace the file in place). The name is reduced to its
     * last segment and rejected if it is not a plain file name, so a crafted original filename
     * cannot escape the directory.
     */
    public synchronized Path stageUpload(String originalFilename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new TlsKeystoreException("error.tls.fileRequired");
        }
        if (bytes.length > MAX_SOURCE_BYTES) {
            throw new TlsKeystoreException("error.tls.fileTooLarge");
        }
        try {
            createDirectory();
            Path uploads = uploadDirectory();
            Files.createDirectories(uploads);
            setPermissions(uploads, PRIVATE_DIR);
            Path target = uploads.resolve(stagedName(safeFileName(originalFilename))).normalize();
            if (!uploads.equals(target.getParent())) {
                throw new TlsKeystoreException("error.tls.pathInvalid");
            }
            // 新名字不会撞上已被配置引用的文件；保存被拒时旧文件原样保留。
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            setPermissions(target, PRIVATE_FILE);
            return target.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.saveFailed", e);
        }
    }

    /** Removes the sidecar, every generation and any staged uploads; idempotent. */
    public synchronized boolean delete() {
        boolean present = Files.isRegularFile(sidecar()) || anyKeystorePresent()
                || anyStagedUpload();
        if (!present) {
            return false;
        }
        try {
            Files.deleteIfExists(sidecar());
            removeKeystores(null);
            removeUploads();
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

    /**
     * Reads the configured certificate, whichever way it was configured.
     *
     * <p>Synchronized because a PEM path mode may rebuild its derived keystore here: the mounted
     * PEM files are the source of truth, and replacing them is how an operator renews the
     * certificate. The rebuild goes through the same generation file + atomic sidecar replace as
     * an upload, so a crash leaves the previous generation readable.
     */
    private synchronized TlsStoreSnapshot read() {
        Path sidecar = sidecar();
        if (!Files.isRegularFile(sidecar)) {
            return TlsStoreSnapshot.empty();
        }
        Properties properties = loadProperties(sidecar);
        requireSupportedVersion(properties);

        String source = properties.getProperty(KEY_SOURCE, SOURCE_UPLOAD);
        return switch (source) {
            case SOURCE_UPLOAD -> readUpload(properties);
            case SOURCE_KEYSTORE_PATH -> readKeystorePath(properties);
            case SOURCE_PEM_PATH -> readPemPath(properties);
            default -> throw new TlsKeystoreException("error.tls.storeCorrupt");
        };
    }

    private void requireSupportedVersion(Properties properties) {
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
    }

    /** The managed-directory upload: generation file copied in, digest checked every read. */
    private TlsStoreSnapshot readUpload(Properties properties) {
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

        TlsConfig config = new TlsConfig(format, file, decryptPassphrase(properties),
                readHttpsPort(properties), readRedirectHttp(properties), readUpdatedAt(properties),
                properties.getProperty("sha256", "").toLowerCase(Locale.ROOT));
        return new TlsStoreSnapshot(java.util.Optional.of(config), null, true, true, null);
    }

    /**
     * A keystore the operator keeps on the server. The file is read where it is, so replacing it
     * in place is the renewal path; the digest is recomputed instead of compared, and the format
     * is proven by opening the current bytes.
     */
    private TlsStoreSnapshot readKeystorePath(Properties properties) {
        Path keystore = requireAbsolutePath(properties.getProperty(KEY_KEYSTORE_PATH));
        byte[] bytes = readSource(keystore);
        String passphrase = decryptPassphrase(properties);
        TlsKeystoreFormat format = TlsConfiguredLoader.detectFormat(keystore, bytes,
                passphrase.toCharArray());

        String path = keystore.toString();
        TlsConfig config = new TlsConfig(format, path, passphrase, readHttpsPort(properties),
                readRedirectHttp(properties), readUpdatedAt(properties), sha256Hex(bytes));
        return new TlsStoreSnapshot(java.util.Optional.of(config), null, true, true,
                new TlsConfiguredPaths(path, null, null));
    }

    /**
     * A PEM pair the operator keeps on the server. Tomcat cannot read PEM, so the pair is wrapped
     * in a managed PKCS#12 at save time; a read that sees different source bytes rebuilds it.
     */
    private TlsStoreSnapshot readPemPath(Properties properties) {
        Path certificate = requireAbsolutePath(properties.getProperty(KEY_PEM_CERT_PATH));
        String keyValue = properties.getProperty(KEY_PEM_KEY_PATH);
        Path key = keyValue == null || keyValue.isBlank() ? null : requireAbsolutePath(keyValue);
        byte[] certificatePem = readSource(certificate);
        byte[] privateKeyPem = key == null ? null : readSource(key);

        String digest = sourceDigest(certificatePem, privateKeyPem);
        String passphrase = decryptPassphrase(properties);
        String file = properties.getProperty("file");
        Path derived = containedOrNull(file);
        boolean changed = derived == null || !Files.isRegularFile(derived)
                || !digest.equalsIgnoreCase(properties.getProperty("sha256", ""));
        if (changed) {
            PemKeyPair pair = privateKeyPem == null
                    ? PemKeyPairLoader.load(certificatePem)
                    : PemKeyPairLoader.load(certificatePem, privateKeyPem);
            byte[] keystore = pair.toPkcs12(passphrase.toCharArray());
            file = generationName(TlsKeystoreFormat.PKCS12);
            try {
                writePrivate(directory.resolve(file), keystore);
                properties.setProperty("file", file);
                properties.setProperty("format", TlsKeystoreFormat.PKCS12.name());
                properties.setProperty("sha256", digest);
                writePropertiesAtomic(properties);
            } catch (IOException e) {
                throw new TlsKeystoreException("error.tls.saveFailed", e);
            }
            cleanupOrphans(file);
        }

        TlsConfig config = new TlsConfig(TlsKeystoreFormat.PKCS12, file, passphrase,
                readHttpsPort(properties), readRedirectHttp(properties), readUpdatedAt(properties),
                digest);
        return new TlsStoreSnapshot(java.util.Optional.of(config), null, true, true,
                new TlsConfiguredPaths(null, certificate.toString(),
                        key == null ? null : key.toString()));
    }

    private static int readHttpsPort(Properties properties) {
        int httpsPort;
        try {
            httpsPort = Integer.parseInt(properties.getProperty("httpsPort", ""));
        } catch (NumberFormatException e) {
            throw new TlsKeystoreException("error.tls.storeCorrupt", e);
        }
        if (httpsPort < 1 || httpsPort > 65535) {
            throw new TlsKeystoreException("error.tls.portInvalid");
        }
        return httpsPort;
    }

    private static Instant readUpdatedAt(Properties properties) {
        try {
            return Instant.parse(properties.getProperty("updatedAt", ""));
        } catch (DateTimeParseException e) {
            throw new TlsKeystoreException("error.tls.storeCorrupt", e);
        }
    }

    private static boolean readRedirectHttp(Properties properties) {
        return Boolean.parseBoolean(properties.getProperty("redirectHttp", "false"));
    }

    /** A page-entered path must be absolute: a relative one would move with the working directory. */
    private static Path requireAbsolutePath(String value) {
        if (value == null || value.isBlank()) {
            throw new TlsKeystoreException("error.tls.pathInvalid");
        }
        Path path;
        try {
            path = Paths.get(value);
        } catch (InvalidPathException e) {
            throw new TlsKeystoreException("error.tls.pathInvalid", e);
        }
        if (!path.isAbsolute()) {
            throw new TlsKeystoreException("error.tls.pathInvalid");
        }
        return path.normalize();
    }

    private static byte[] readSource(Path path) {
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new TlsKeystoreException("error.tls.pathMissing");
            }
            // Check the size before reading: a mistyped path to a large backup would OOM.
            if (Files.size(path) > MAX_SOURCE_BYTES) {
                throw new TlsKeystoreException("error.tls.fileTooLarge");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_SOURCE_BYTES) {
                throw new TlsKeystoreException("error.tls.fileTooLarge");
            }
            return bytes;
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.pathMissing", e);
        }
    }

    /** A sidecar generation name, or {@code null} when it is missing; traversal still fails. */
    private Path containedOrNull(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        return resolveContained(file);
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

    private void writeSidecar(TlsConfig config, String source, String keystorePath,
                              String pemCertPath, String pemKeyPath) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", String.valueOf(VERSION));
        properties.setProperty(KEY_SOURCE, source);
        properties.setProperty("format", config.format().name());
        properties.setProperty("file", config.file());
        properties.setProperty(KEY_PASSPHRASE, crypto.encrypt(config.passphrase()));
        properties.setProperty("httpsPort", String.valueOf(config.httpsPort()));
        properties.setProperty("redirectHttp", String.valueOf(config.redirectHttp()));
        properties.setProperty("updatedAt", config.updatedAt().toString());
        properties.setProperty("sha256", config.sha256());
        if (keystorePath != null) {
            properties.setProperty(KEY_KEYSTORE_PATH, keystorePath);
        }
        if (pemCertPath != null) {
            properties.setProperty(KEY_PEM_CERT_PATH, pemCertPath);
        }
        if (pemKeyPath != null) {
            properties.setProperty(KEY_PEM_KEY_PATH, pemKeyPath);
        }
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

    private Path uploadDirectory() {
        return directory.resolve(UPLOAD_DIR);
    }

    /**
     * A collision-free staged name, keeping the client name readable as the suffix.
     *
     * <p>Staging must never overwrite a file the saved configuration points at: a failed save
     * (wrong passphrase, mismatched PEM pair, expired certificate) would otherwise destroy the
     * certificate that is currently working.
     */
    private static String stagedName(String name) {
        byte[] random = new byte[4];
        RANDOM.nextBytes(random);
        StringBuilder hex = new StringBuilder();
        for (byte b : random) {
            hex.append(String.format("%02x", b));
        }
        return "staged-" + System.currentTimeMillis() + "-" + hex + "-" + name;
    }

    /** The last segment of the client name, or a key if that is not a plain file name. */
    private static String safeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new TlsKeystoreException("error.tls.pathInvalid");
        }
        String name;
        try {
            Path path = Paths.get(originalFilename).getFileName();
            name = path == null ? "" : path.toString();
        } catch (InvalidPathException e) {
            throw new TlsKeystoreException("error.tls.pathInvalid", e);
        }
        if (name.isBlank() || ".".equals(name) || "..".equals(name)
                || name.indexOf('\\') >= 0 || name.chars().anyMatch(Character::isISOControl)) {
            throw new TlsKeystoreException("error.tls.pathInvalid");
        }
        return name;
    }

    /**
     * Removes staged files that are neither named by the new configuration nor recent enough to
     * belong to a page still being filled in.
     *
     * <p>"Not named by this save" alone is not staleness: another tab (or another administrator)
     * may have staged a file seconds ago and not saved yet, and deleting it would leave that
     * path input pointing at nothing. Only files past {@link #STAGING_RETENTION} are removed. A
     * hand-typed external path matches no staged file, so nothing outside {@code uploads/} is
     * ever touched.
     */
    private void pruneUploads(Path... keep) {
        Path uploads = uploadDirectory();
        if (!Files.isDirectory(uploads)) {
            return;
        }
        Instant cutoff = Instant.now().minus(STAGING_RETENTION);
        Set<Path> retained = Arrays.stream(keep)
                .filter(java.util.Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toSet());
        try (Stream<Path> stream = Files.list(uploads)) {
            for (Path path : stream.collect(Collectors.toList())) {
                if (Files.isRegularFile(path)
                        && !retained.contains(path.toAbsolutePath().normalize())
                        && olderThan(path, cutoff)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            log.warn("Could not remove an older staged TLS upload: {}", e.toString());
        }
    }

    /** An unreadable timestamp keeps the file: deleting on doubt is the worse failure. */
    private static boolean olderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private void removeUploads() throws IOException {
        Path uploads = uploadDirectory();
        if (!Files.isDirectory(uploads)) {
            return;
        }
        try (Stream<Path> stream = Files.list(uploads)) {
            for (Path path : stream.collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
        Files.deleteIfExists(uploads);
    }

    private boolean anyStagedUpload() {
        Path uploads = uploadDirectory();
        if (!Files.isDirectory(uploads)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(uploads)) {
            return stream.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
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
                if (GENERATED_KEYSTORE.matcher(name).matches() && !name.equals(keep)) {
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
            return stream.anyMatch(path -> GENERATED_KEYSTORE.matcher(
                    path.getFileName().toString()).matches());
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

    /**
     * The identity of a PEM pair. Hashing the sources, not the derived PKCS#12, keeps the digest
     * stable across restarts: PKCS#12 writing uses random salts, so the same pair produces
     * different bytes every time it is wrapped.
     */
    private static String sourceDigest(byte[] certificatePem, byte[] privateKeyPem) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(certificatePem);
            if (privateKeyPem != null) {
                digest.update(privateKeyPem);
            }
            return hex(digest.digest());
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
