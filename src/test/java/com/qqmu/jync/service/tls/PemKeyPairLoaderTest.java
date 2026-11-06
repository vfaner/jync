package com.qqmu.jync.service.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * Turning the two files an nginx operator already has — certificate chain and private key — into
 * the one key entry Tomcat can serve.
 *
 * <p>Everything that would fail at the next boot is rejected here: an unparseable certificate, a
 * key that does not belong to it, an encrypted key this page has no passphrase field for.
 */
class PemKeyPairLoaderTest {

    @Test
    void loadsTheCertificateAndAPkcs8Key() {
        PemKeyPair pair = PemKeyPairLoader.load(
                bytes(TestKeystores.certificatePem()), bytes(TestKeystores.privateKeyPem()));

        assertThat(pair.chain()).hasSize(1);
        assertThat(pair.chain()[0].getSubjectX500Principal().getName())
                .contains("CN=misconfigured.example");
        assertThat(pair.key().getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void loadsAPkcs1RsaKey() {
        PemKeyPair pair = PemKeyPairLoader.load(
                bytes(TestKeystores.certificatePem()), bytes(TestKeystores.rsaPrivateKeyPem()));

        assertThat(pair.key().getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void loadsACertificateAndKeyFromOneCombinedFile() {
        String combined = TestKeystores.certificatePem() + TestKeystores.privateKeyPem();

        PemKeyPair pair = PemKeyPairLoader.load(bytes(combined));

        assertThat(pair.chain()).hasSize(1);
        assertThat(pair.chain()[0].getSubjectX500Principal().getName())
                .contains("CN=misconfigured.example");
        assertThat(pair.key().getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void aCombinedFileWithoutAPrivateKeyIsRejected() {
        // The operator picked a certificate-only file; the form's key field can fill the gap.
        assertThatThrownBy(() -> PemKeyPairLoader.load(bytes(TestKeystores.certificatePem())))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemKeyInvalid");
    }

    @Test
    void keepsTheChainInTheOrderTheFileGivesIt() {
        String chain = TestKeystores.certificatePem() + TestKeystores.certificatePem();

        PemKeyPair pair = PemKeyPairLoader.load(bytes(chain), bytes(TestKeystores.privateKeyPem()));

        assertThat(pair.chain()).hasSize(2);
    }

    @Test
    void loadsADerCertificateFileWithASeparatePemKey() throws Exception {
        // Operators export ".cer" from a browser or a Windows CA; it is DER without PEM armour.
        byte[] der = TestKeystores.certificate().getEncoded();

        PemKeyPair pair = PemKeyPairLoader.load(der, bytes(TestKeystores.privateKeyPem()));

        assertThat(pair.chain()).hasSize(1);
        assertThat(pair.chain()[0].getSubjectX500Principal().getName())
                .contains("CN=misconfigured.example");
    }

    @Test
    void rejectsACertificateBlockThatIsNotACertificate() {
        assertThatThrownBy(() -> PemKeyPairLoader.load(
                bytes(TestKeystores.pem("CERTIFICATE", new byte[] {1, 2, 3})),
                bytes(TestKeystores.privateKeyPem())))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemCertInvalid");
    }

    @Test
    void rejectsAFileWithoutAPrivateKey() {
        assertThatThrownBy(() -> PemKeyPairLoader.load(
                bytes(TestKeystores.certificatePem()),
                bytes("not a pem file at all")))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemKeyInvalid");
    }

    @Test
    void rejectsAnEncryptedPrivateKey() {
        String encrypted = TestKeystores.pem("ENCRYPTED PRIVATE KEY", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> PemKeyPairLoader.load(
                bytes(TestKeystores.certificatePem()), bytes(encrypted)))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemKeyEncrypted");
    }

    @Test
    void rejectsALegacySec1EcKeyWithAnExplanation() {
        String sec1 = TestKeystores.pem("EC PRIVATE KEY", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> PemKeyPairLoader.load(
                bytes(TestKeystores.certificatePem()), bytes(sec1)))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemKeyUnsupported");
    }

    @Test
    void rejectsAKeyThatDoesNotMatchTheCertificate() {
        assertThatThrownBy(() -> PemKeyPairLoader.load(
                bytes(TestKeystores.certificatePem()),
                bytes(TestKeystores.unrelatedPrivateKeyPem())))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemKeyMismatch");
    }

    @Test
    void wrapsTheMaterialIntoAPkcs12ThatOpensWithTheGivenPassphrase() throws Exception {
        PemKeyPair pair = PemKeyPairLoader.load(
                bytes(TestKeystores.certificatePem()), bytes(TestKeystores.privateKeyPem()));
        char[] passphrase = "generated-passphrase".toCharArray();

        byte[] bytes = pair.toPkcs12(passphrase);

        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(bytes), passphrase);
        String alias = null;
        for (String candidate : Collections.list(store.aliases())) {
            if (store.isKeyEntry(candidate)) {
                alias = candidate;
            }
        }
        assertThat(alias).isNotNull();
        java.security.cert.Certificate[] chain = store.getCertificateChain(alias);
        assertThat(chain).hasSize(1);
        assertThat(chain[0]).isInstanceOf(X509Certificate.class);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
