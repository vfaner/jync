package com.qqmu.jync.service.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TlsCertificateInspectorTest {

    private final TlsCertificateInspector inspector = new TlsCertificateInspector();

    @Test
    void readsSubjectIssuerAndDates() {
        TlsCertificateInfo info = inspector.inspect(TestKeystores.pkcs12(),
                TlsKeystoreFormat.PKCS12, TestKeystores.PASSPHRASE);

        assertThat(info.subject()).contains("CN=misconfigured.example");
        assertThat(info.issuer()).isEqualTo(info.subject());
        assertThat(info.notAfter()).isAfter(Instant.parse("2100-01-01T00:00:00Z"));
        assertThat(info.notBefore()).isBefore(info.notAfter());
    }

    @Test
    void returnsRsaAndA64CharFingerprint() {
        TlsCertificateInfo info = inspector.inspect(TestKeystores.pkcs12(),
                TlsKeystoreFormat.PKCS12, TestKeystores.PASSPHRASE);

        assertThat(info.publicKeyAlgorithm()).isEqualToIgnoringCase("RSA");
        assertThat(info.sha256Fingerprint()).matches("[0-9a-f]{64}");
        assertThat(info.alias()).isNotBlank();
    }

    @Test
    void readsJksKeystores() {
        TlsCertificateInfo pkcs12 = inspector.inspect(TestKeystores.pkcs12(),
                TlsKeystoreFormat.PKCS12, TestKeystores.PASSPHRASE);
        TlsCertificateInfo jks = inspector.inspect(TestKeystores.jks(),
                TlsKeystoreFormat.JKS, TestKeystores.PASSPHRASE);

        assertThat(jks.subject()).isEqualTo(pkcs12.subject());
        assertThat(jks.sha256Fingerprint()).isEqualTo(pkcs12.sha256Fingerprint());
    }

    @Test
    void rejectsTheWrongPassphrase() {
        assertThatThrownBy(() -> inspector.inspect(TestKeystores.pkcs12(),
                TlsKeystoreFormat.PKCS12, "wrong-passphrase".toCharArray()))
                .isInstanceOf(TlsKeystoreException.class)
                .satisfies(e -> assertThat(((TlsKeystoreException) e).messageKey())
                        .isEqualTo("error.tls.unreadable"));
    }

    @Test
    void rejectsAStoreWithoutAPrivateKey() throws Exception {
        byte[] store = storeWithCertificateOnly();

        assertThatThrownBy(() -> inspector.inspect(store, TlsKeystoreFormat.PKCS12,
                TestKeystores.PASSPHRASE))
                .isInstanceOf(TlsKeystoreException.class)
                .satisfies(e -> assertThat(((TlsKeystoreException) e).messageKey())
                        .isEqualTo("error.tls.noPrivateKey"));
    }

    @Test
    void rejectsTwoPrivateKeys() throws Exception {
        byte[] store = storeWithTwoKeyEntries();

        assertThatThrownBy(() -> inspector.inspect(store, TlsKeystoreFormat.PKCS12,
                TestKeystores.PASSPHRASE))
                .isInstanceOf(TlsKeystoreException.class)
                .satisfies(e -> assertThat(((TlsKeystoreException) e).messageKey())
                        .isEqualTo("error.tls.multipleKeys"));
    }

    @Test
    void rejectsAKeyThatDoesNotMatchItsCertificate() throws Exception {
        // Tomcat accepts such a store at startup and fails every handshake, so the upload
        // must be refused while the page can still explain it.
        byte[] store = storeWithUnrelatedKey();

        assertThatThrownBy(() -> inspector.inspect(store, TlsKeystoreFormat.PKCS12,
                TestKeystores.PASSPHRASE))
                .isInstanceOf(TlsKeystoreException.class)
                .satisfies(e -> assertThat(((TlsKeystoreException) e).messageKey())
                        .isEqualTo("error.tls.keyMismatch"));
    }

    @Test
    void doesNotLeakThePassphraseInTheException() {
        try {
            inspector.inspect(TestKeystores.pkcs12(), TlsKeystoreFormat.PKCS12,
                    "wrong-passphrase".toCharArray());
        } catch (TlsKeystoreException e) {
            assertThat(e.getMessage()).isEqualTo("error.tls.unreadable");
            assertThat(e.toString()).doesNotContain("changeit", "wrong-passphrase");
            assertThat(String.valueOf(e.getCause())).doesNotContain("wrong-passphrase");
            return;
        }
        throw new AssertionError("expected a TlsKeystoreException");
    }

    private static byte[] storeWithCertificateOnly() throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        source.load(new ByteArrayInputStream(TestKeystores.pkcs12()), TestKeystores.PASSPHRASE);
        Certificate certificate = source.getCertificate(source.aliases().nextElement());

        KeyStore target = KeyStore.getInstance("PKCS12");
        target.load(null, TestKeystores.PASSPHRASE);
        target.setCertificateEntry("only-cert", certificate);
        return encoded(target);
    }

    private static byte[] storeWithTwoKeyEntries() throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        source.load(new ByteArrayInputStream(TestKeystores.pkcs12()), TestKeystores.PASSPHRASE);
        String alias = source.aliases().nextElement();
        Key key = source.getKey(alias, TestKeystores.PASSPHRASE);
        Certificate[] chain = source.getCertificateChain(alias);

        KeyStore target = KeyStore.getInstance("PKCS12");
        target.load(null, TestKeystores.PASSPHRASE);
        target.setKeyEntry("first", key, TestKeystores.PASSPHRASE, chain);
        target.setKeyEntry("second", key, TestKeystores.PASSPHRASE, chain);
        return encoded(target);
    }

    private static byte[] storeWithUnrelatedKey() throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        source.load(new ByteArrayInputStream(TestKeystores.pkcs12()), TestKeystores.PASSPHRASE);
        String alias = source.aliases().nextElement();
        Certificate[] chain = source.getCertificateChain(alias);
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator
                .getInstance("RSA");
        generator.initialize(2048);

        KeyStore target = KeyStore.getInstance("PKCS12");
        target.load(null, TestKeystores.PASSPHRASE);
        target.setKeyEntry("mismatch", generator.generateKeyPair().getPrivate(),
                TestKeystores.PASSPHRASE, chain);
        return encoded(target);
    }

    private static byte[] encoded(KeyStore store) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.store(out, TestKeystores.PASSPHRASE);
        return out.toByteArray();
    }
}
