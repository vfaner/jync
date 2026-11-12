package com.qqmu.jync.service.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the {@link TlsPathSource} paths into the same {@link TlsConfig} an upload produces.
 *
 * <p>The keystore is read where the operator mounted it; its type is proven by opening it, so a
 * mounted file may be named anything. A PEM pair is wrapped in a PKCS#12 under a generated
 * passphrase and written to the system temporary directory (private permissions, removed on
 * exit): Tomcat only reads keystores, and the plaintext private key still never lands on disk.
 *
 * <p>Everything is validated here at startup, like the saved certificate path: a missing or
 * unreadable file stops the boot instead of serving an unexpected certificate. The documented
 * way back in is {@code app.tls.enabled=false}.
 */
@Component
public class TlsConfiguredLoader {

    private static final Logger log = LoggerFactory.getLogger(TlsConfiguredLoader.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TlsPathSource source;

    public TlsConfiguredLoader(TlsPathSource source) {
        this.source = source;
    }

    /** No configured source, for tests and callers that only use the managed directory. */
    static TlsConfiguredLoader none() {
        return new TlsConfiguredLoader(TlsPathSource.none());
    }

    public Optional<TlsConfigured> load() {
        if (!source.configured()) {
            return Optional.empty();
        }
        return Optional.of(source.keystore().isPresent() ? fromKeystore() : fromPem());
    }

    private TlsConfigured fromKeystore() {
        Path path = source.keystore().orElseThrow();
        requireFile(path);
        byte[] bytes = read(path);
        String password = source.keystorePassword();
        TlsKeystoreFormat format = detectFormat(path, bytes, password.toCharArray());

        TlsConfig config = new TlsConfig(format, path.toString(), password, source.httpsPort(),
                source.redirectHttp(), modifiedAt(path), sha256(bytes));
        return new TlsConfigured(config, new TlsConfiguredPaths(path.toString(), null, null));
    }

    private TlsConfigured fromPem() {
        Path certificate = source.pemCertificate().orElseThrow();
        requireFile(certificate);
        Path key = source.pemKey().orElse(null);
        if (key != null) {
            requireFile(key);
        }

        PemKeyPair pair = key == null
                ? PemKeyPairLoader.load(read(certificate))
                : PemKeyPairLoader.load(read(certificate), read(key));
        char[] secret = randomPassphrase();
        byte[] keystore = pair.toPkcs12(secret);
        Path temporary = writeTemporaryKeystore(keystore);

        TlsConfig config = new TlsConfig(TlsKeystoreFormat.PKCS12, temporary.toString(),
                new String(secret), source.httpsPort(), source.redirectHttp(),
                modifiedAt(certificate), sha256(keystore));
        return new TlsConfigured(config, new TlsConfiguredPaths(null, certificate.toString(),
                key == null ? null : key.toString()));
    }

    /**
     * The real container is proven by opening the bytes, not by the file name. The magic number
     * is consulted first (and must stay first): since JDK 9 the PKCS#12 implementation can load
     * a JKS file through {@code keystore.type.compat}, so trying PKCS#12 first would label every
     * mounted JKS as PKCS#12. The extension is only a hint; a wrong passphrase fails every
     * container and is reported as such.
     */
    static TlsKeystoreFormat detectFormat(Path path, byte[] bytes, char[] passphrase) {
        Set<TlsKeystoreFormat> candidates = new LinkedHashSet<>();
        magicFormat(bytes).ifPresent(candidates::add);
        TlsKeystoreFormat.fromFilename(path.getFileName().toString()).ifPresent(candidates::add);
        candidates.add(TlsKeystoreFormat.PKCS12);
        candidates.add(TlsKeystoreFormat.JKS);
        for (TlsKeystoreFormat format : candidates) {
            try {
                KeyStore store = KeyStore.getInstance(format.keystoreType());
                store.load(new ByteArrayInputStream(bytes), passphrase);
                return format;
            } catch (GeneralSecurityException | IOException ignored) {
                // Try the next container: either the name lied or the passphrase is wrong.
            }
        }
        throw new TlsKeystoreException("error.tls.unreadable");
    }

    /** JKS/JCEKS carry a magic number; PKCS#12 is DER and starts with a SEQUENCE. */
    private static Optional<TlsKeystoreFormat> magicFormat(byte[] bytes) {
        if (bytes.length >= 4
                && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xED
                && (bytes[2] & 0xFF) == 0xFE && (bytes[3] & 0xFF) == 0xED) {
            return Optional.of(TlsKeystoreFormat.JKS);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x30
                && ((bytes[1] & 0xFF) == 0x81 || (bytes[1] & 0xFF) == 0x82
                    || (bytes[1] & 0xFF) < 0x80)) {
            return Optional.of(TlsKeystoreFormat.PKCS12);
        }
        return Optional.empty();
    }

    private static void requireFile(Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new TlsKeystoreException("error.tls.configFileMissing");
        }
    }

    private static byte[] read(Path path) {
        try {
            // Same ceiling as a page upload: a misdirected variable must not OOM the boot.
            if (Files.size(path) > TlsKeystoreStore.MAX_SOURCE_BYTES) {
                throw new TlsKeystoreException("error.tls.fileTooLarge");
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.configLoadFailed", e);
        }
    }

    private static Path writeTemporaryKeystore(byte[] bytes) {
        try {
            Path path = Files.createTempFile("jync-tls-", ".p12");
            Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException e) {
                log.warn("Could not restrict permissions on {}: {}", path, e.toString());
            }
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.configLoadFailed", e);
        }
    }

    private static Instant modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }

    private static char[] randomPassphrase() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE", e);
        }
    }
}
