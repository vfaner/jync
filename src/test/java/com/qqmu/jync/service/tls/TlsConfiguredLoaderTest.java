package com.qqmu.jync.service.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Turns the configured paths into the same {@link TlsConfig} the page upload produces.
 *
 * <p>The point of the path mode is a container whose certificate is mounted read-only: the
 * material must be validated at startup exactly like an upload, but nothing is written into
 * {@code app.tls.directory}. A PEM pair is wrapped in an encrypted temporary PKCS#12 because
 * Tomcat only reads keystores, just like the upload path does.
 */
class TlsConfiguredLoaderTest {

    @TempDir
    Path dir;

    private Path write(String name, byte[] bytes) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    private Path write(String name, String text) throws IOException {
        return write(name, text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void loadsNothingWhenNothingIsConfigured() {
        TlsConfiguredLoader loader = new TlsConfiguredLoader(new TlsPathSource("", "", "", "",
                "", ""));

        assertThat(loader.load()).isEmpty();
    }

    @Test
    void loadsAConfiguredPkcs12Keystore() throws IOException {
        Path keystore = write("jync.p12", TestKeystores.pkcs12());
        TlsConfiguredLoader loader = new TlsConfiguredLoader(new TlsPathSource(
                keystore.toString(), "changeit", null, null, "9443", "false"));

        TlsConfigured loaded = loader.load().orElseThrow();

        assertThat(loaded.config().format()).isEqualTo(TlsKeystoreFormat.PKCS12);
        assertThat(Paths.get(loaded.config().file())).isEqualTo(keystore);
        assertThat(loaded.config().passphrase()).isEqualTo("changeit");
        assertThat(loaded.config().httpsPort()).isEqualTo(9443);
        assertThat(loaded.config().redirectHttp()).isFalse();
        assertThat(loaded.config().sha256()).hasSize(64);
        assertThat(loaded.paths().keystorePath()).isEqualTo(keystore.toString());
        assertThat(loaded.paths().pemCertificatePath()).isNull();
        // The synthesized config opens with the operator's passphrase, as the connector will.
        new TlsCertificateInspector().inspect(Files.readAllBytes(keystore),
                loaded.config().format(), loaded.config().passphrase().toCharArray());
    }

    @Test
    void detectsAJksKeystoreUnderAnUnknownExtension() throws IOException {
        // Operators name mounted files anything; the container type is proven by opening it.
        Path keystore = write("server.keystore", TestKeystores.jks());
        TlsConfiguredLoader loader = new TlsConfiguredLoader(new TlsPathSource(
                keystore.toString(), "changeit", null, null, null, null));

        assertThat(loader.load().orElseThrow().config().format())
                .isEqualTo(TlsKeystoreFormat.JKS);
    }

    @Test
    void rejectsAMissingKeystoreFile() {
        TlsConfiguredLoader loader = new TlsConfiguredLoader(new TlsPathSource(
                dir.resolve("absent.p12").toString(), "changeit", null, null, null, null));

        assertThatThrownBy(loader::load)
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.configFileMissing");
    }

    @Test
    void rejectsTheWrongPassphrase() throws IOException {
        Path keystore = write("jync.p12", TestKeystores.pkcs12());
        TlsConfiguredLoader loader = new TlsConfiguredLoader(new TlsPathSource(
                keystore.toString(), "wrong", null, null, null, null));

        assertThatThrownBy(loader::load)
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.unreadable");
    }

    @Test
    void loadsAPemPairIntoAnEncryptedTemporaryKeystore() throws IOException {
        Path certificate = write("fullchain.pem", TestKeystores.certificatePem());
        Path key = write("privkey.pem", TestKeystores.privateKeyPem());
        TlsConfiguredLoader loader = new TlsConfiguredLoader(new TlsPathSource(null, null,
                certificate.toString(), key.toString(), null, null));

        TlsConfigured loaded = loader.load().orElseThrow();
        TlsConfig config = loaded.config();

        assertThat(config.format()).isEqualTo(TlsKeystoreFormat.PKCS12);
        Path temporary = Paths.get(config.file());
        assertThat(Files.isRegularFile(temporary)).isTrue();
        // The plaintext key never lands on disk; the temporary wrapper is encrypted.
        String content = new String(Files.readAllBytes(temporary), StandardCharsets.ISO_8859_1);
        assertThat(content).doesNotContain("BEGIN PRIVATE KEY", "BEGIN RSA PRIVATE KEY");
        TlsCertificateInfo info = new TlsCertificateInspector().inspect(
                Files.readAllBytes(temporary), TlsKeystoreFormat.PKCS12,
                config.passphrase().toCharArray());
        assertThat(info.subject()).contains("CN=misconfigured.example");

        assertThat(loaded.paths().keystorePath()).isNull();
        assertThat(loaded.paths().pemCertificatePath()).isEqualTo(certificate.toString());
        assertThat(loaded.paths().pemKeyPath()).isEqualTo(key.toString());
    }

    @Test
    void loadsACombinedPemBundleWithoutASeparateKey() throws IOException {
        Path bundle = write("bundle.pem",
                TestKeystores.certificatePem() + TestKeystores.privateKeyPem());
        TlsConfiguredLoader loader = new TlsConfiguredLoader(new TlsPathSource(null, null,
                bundle.toString(), null, null, null));

        TlsConfigured loaded = loader.load().orElseThrow();

        assertThat(loaded.paths().pemCertificatePath()).isEqualTo(bundle.toString());
        assertThat(loaded.paths().pemKeyPath()).isNull();
        new TlsCertificateInspector().inspect(Files.readAllBytes(Paths.get(loaded.config().file())),
                loaded.config().format(), loaded.config().passphrase().toCharArray());
    }

    @Test
    void rejectsACertificateFileWithoutAPrivateKey() throws IOException {
        Path certificate = write("fullchain.pem", TestKeystores.certificatePem());
        TlsConfiguredLoader loader = new TlsConfiguredLoader(new TlsPathSource(null, null,
                certificate.toString(), null, null, null));

        assertThatThrownBy(loader::load)
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemKeyInvalid");
    }

    @Test
    void rejectsAMismatchedPemKey() throws IOException {
        Path certificate = write("fullchain.pem", TestKeystores.certificatePem());
        Path key = write("other.pem", TestKeystores.unrelatedPrivateKeyPem());
        TlsConfiguredLoader loader = new TlsConfiguredLoader(new TlsPathSource(null, null,
                certificate.toString(), key.toString(), null, null));

        assertThatThrownBy(loader::load)
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemKeyMismatch");
    }
}
