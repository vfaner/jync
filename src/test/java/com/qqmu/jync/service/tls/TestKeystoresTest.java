package com.qqmu.jync.service.tls;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * Pins the test fixture itself: every store test depends on these bytes being a keystore with
 * exactly one private key and a certificate that is still valid.
 */
class TestKeystoresTest {

    @Test
    void fixturePkcs12OpensWithTheKnownPassphrase() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(TestKeystores.pkcs12()), TestKeystores.PASSPHRASE);

        assertThat(Collections.list(store.aliases()))
                .filteredOn(alias -> {
                    try {
                        return store.isKeyEntry(alias);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .hasSize(1);

        assertThat(notAfter(store)).isAfter(Instant.parse("2100-01-01T00:00:00Z"));
    }

    @Test
    void fixtureJksIsDerivedFromThePkcs12() throws Exception {
        KeyStore pkcs12 = KeyStore.getInstance("PKCS12");
        pkcs12.load(new ByteArrayInputStream(TestKeystores.pkcs12()), TestKeystores.PASSPHRASE);
        KeyStore jks = KeyStore.getInstance("JKS");
        jks.load(new ByteArrayInputStream(TestKeystores.jks()), TestKeystores.PASSPHRASE);

        assertThat(notAfter(jks)).isEqualTo(notAfter(pkcs12));
        assertThat(jks.size()).isEqualTo(pkcs12.size());
    }

    private static Instant notAfter(KeyStore store) throws Exception {
        for (String alias : Collections.list(store.aliases())) {
            Certificate certificate = store.getCertificate(alias);
            if (certificate instanceof X509Certificate) {
                return ((X509Certificate) certificate).getNotAfter().toInstant();
            }
        }
        throw new AssertionError("fixture has no certificate");
    }
}
