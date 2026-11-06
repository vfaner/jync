package com.qqmu.jync.service.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.util.CryptoUtil;

class TlsKeystoreStoreTest {

    private static final String PASSPHRASE = "changeit";

    @TempDir
    Path dir;

    private static CryptoUtil crypto(String password, String salt) {
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword(password);
        properties.setCryptoSalt(salt);
        return new CryptoUtil(properties);
    }

    private TlsKeystoreStore store() {
        return new TlsKeystoreStore(dir.toString(),
                crypto("store-password", "0123456789abcdef"));
    }

    private static byte[] keystore(String marker) {
        byte[] bytes = TestKeystores.pkcs12();
        bytes[0] = (byte) (bytes[0] ^ marker.hashCode());
        return bytes;
    }

    @Test
    void writesSidecarAndKeystore() throws Exception {
        TlsKeystoreStore store = store();
        byte[] bytes = keystore("one");

        TlsConfig saved = store.save(bytes, TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, true);

        assertThat(saved.httpsPort()).isEqualTo(8443);
        assertThat(saved.redirectHttp()).isTrue();
        assertThat(saved.format()).isEqualTo(TlsKeystoreFormat.PKCS12);
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(saved.sha256()).isEqualTo(sha256(bytes));
        assertThat(dir.resolve(saved.file())).hasBinaryContent(bytes);

        TlsStoreSnapshot snapshot = store.snapshot();
        assertThat(snapshot.problemKey()).isNull();
        assertThat(snapshot.sidecarPresent()).isTrue();
        assertThat(snapshot.config()).isPresent();
        assertThat(snapshot.config().get().passphrase()).isEqualTo(PASSPHRASE);
    }

    @Test
    void doesNotWriteThePassphraseInPlaintext() throws Exception {
        store().save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);

        String sidecar = Files.readString(dir.resolve("tls.properties"), StandardCharsets.UTF_8);
        assertThat(sidecar).doesNotContain(PASSPHRASE);
        assertThat(sidecar).contains("enc:");
    }

    @Test
    void restrictsDirectoryAndFilePermissions() throws Exception {
        TlsConfig saved = store().save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);

        assertThat(Files.getPosixFilePermissions(dir))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(dir.resolve("tls.properties")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(dir.resolve(saved.file())))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void replacesThePreviousGeneration() throws Exception {
        TlsKeystoreStore store = store();
        TlsConfig first = store.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);
        TlsConfig second = store.save(keystore("two"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);

        assertThat(first.file()).isNotEqualTo(second.file());
        assertThat(dir.resolve(first.file())).doesNotExist();
        assertThat(listNames()).containsExactlyInAnyOrder("tls.properties", second.file());
    }

    @Test
    void rejectsPathTraversalInTheSidecar() throws Exception {
        TlsKeystoreStore store = store();
        store.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);
        rewriteSidecarFile("file=../evil.p12");

        TlsStoreSnapshot snapshot = store.snapshot();
        assertThat(snapshot.problemKey()).isEqualTo("error.tls.keystorePath");
        assertThat(snapshot.config()).isEmpty();
        assertThat(snapshot.sidecarPresent()).isTrue();
    }

    @Test
    void rejectsAnAbsolutePathOutsideTheDirectory() throws Exception {
        TlsKeystoreStore store = store();
        store.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);
        rewriteSidecarFile("file=/etc/passwd");

        assertThat(store.snapshot().problemKey()).isEqualTo("error.tls.keystorePath");
    }

    @Test
    void reportsACorruptSidecarWithoutThrowing() throws Exception {
        store().save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);
        rewriteSidecarFile("this is not a properties file \\u00zz");

        TlsStoreSnapshot snapshot = store().snapshot();
        assertThat(snapshot.problemKey()).isEqualTo("error.tls.storeCorrupt");
        assertThat(snapshot.sidecarPresent()).isTrue();
    }

    @Test
    void reportsAMissingKeystoreFile() throws Exception {
        TlsKeystoreStore store = store();
        TlsConfig saved = store.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);
        Files.delete(dir.resolve(saved.file()));

        assertThat(store.snapshot().problemKey()).isEqualTo("error.tls.keystoreMissing");
    }

    @Test
    void reportsADigestMismatch() throws Exception {
        TlsKeystoreStore store = store();
        TlsConfig saved = store.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);
        Files.write(dir.resolve(saved.file()), keystore("tampered"));

        assertThat(store.snapshot().problemKey()).isEqualTo("error.tls.keystoreChanged");
    }

    @Test
    void strictLoadThrowsTheSameKey() throws Exception {
        TlsKeystoreStore store = store();
        TlsConfig saved = store.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);
        Files.write(dir.resolve(saved.file()), keystore("tampered"));

        assertThatThrownBy(store::requireConfig)
                .isInstanceOf(TlsKeystoreException.class)
                .satisfies(e -> assertThat(((TlsKeystoreException) e).messageKey())
                        .isEqualTo("error.tls.keystoreChanged"));
    }

    @Test
    void deleteRemovesBothAndIsIdempotent() throws Exception {
        TlsKeystoreStore store = store();
        TlsConfig saved = store.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);

        assertThat(store.delete()).isTrue();
        assertThat(dir.resolve("tls.properties")).doesNotExist();
        assertThat(dir.resolve(saved.file())).doesNotExist();
        assertThat(store.snapshot().config()).isEmpty();
        assertThat(store.delete()).isFalse();
    }

    @Test
    void deleteWorksWhenTheSidecarIsCorrupt() throws Exception {
        TlsKeystoreStore store = store();
        store.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);
        rewriteSidecarFile("not=parseable=\\u00zz");

        assertThat(store.delete()).isTrue();
        assertThat(listNames()).isEmpty();
    }

    @Test
    void cleanupOrphansKeepsTheReferencedGeneration() throws Exception {
        TlsKeystoreStore store = store();
        store.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);
        Files.write(dir.resolve("keystore-old.p12"), new byte[]{1});

        TlsConfig second = store.save(keystore("two"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);

        assertThat(listNames()).containsExactlyInAnyOrder("tls.properties", second.file());
        assertThat(dir.resolve(second.file())).exists();
    }

    @Test
    void reencryptPassphrasesWritesCiphertextTheNewKeyCanRead() throws Exception {
        TlsKeystoreStore oldKey = store();
        oldKey.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);

        TextEncryptor newKey = Encryptors.delux("rotated-password", "fedcba9876543210");
        oldKey.reencryptPassphrases(newKey);

        TlsKeystoreStore rotated = new TlsKeystoreStore(dir.toString(),
                crypto("rotated-password", "fedcba9876543210"));
        assertThat(rotated.requireConfig().passphrase()).isEqualTo(PASSPHRASE);
    }

    @Test
    void requireConfigFallsBackToTheNextCiphertext() throws Exception {
        TlsKeystoreStore oldKey = store();
        oldKey.save(keystore("one"), TlsKeystoreFormat.PKCS12,
                PASSPHRASE.toCharArray(), 8443, false);
        oldKey.reencryptPassphrases(Encryptors.delux("rotated-password", "fedcba9876543210"));

        String sidecar = Files.readString(dir.resolve("tls.properties"), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("passphrase.next=");

        TlsKeystoreStore rotated = new TlsKeystoreStore(dir.toString(),
                crypto("rotated-password", "fedcba9876543210"));
        assertThat(rotated.requireConfig().passphrase()).isEqualTo(PASSPHRASE);
    }

    @Test
    void reencryptPassphrasesIsANoOpWhenNothingIsSaved() {
        TlsKeystoreStore store = store();
        store.reencryptPassphrases(Encryptors.delux("rotated-password", "fedcba9876543210"));

        assertThat(store.snapshot().config()).isEmpty();
        assertThat(dir.resolve("tls.properties")).doesNotExist();
    }

    private void rewriteSidecarFile(String replacementLine) throws IOException {
        Path sidecar = dir.resolve("tls.properties");
        List<String> lines = Files.readAllLines(sidecar, StandardCharsets.UTF_8);
        List<String> rewritten = lines.stream()
                .map(line -> line.startsWith("file=") ? replacementLine : line)
                .collect(Collectors.toList());
        if (!replacementLine.startsWith("file=")) {
            rewritten.add(replacementLine);
        }
        Files.write(sidecar, rewritten, StandardCharsets.UTF_8);
    }

    private List<String> listNames() throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(path -> path.getFileName().toString()).collect(Collectors.toList());
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
