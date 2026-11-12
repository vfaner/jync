package com.qqmu.jync.service.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * The certificate paths an operator mounts into the container and names in the configuration.
 *
 * <p>Parsing only: whether the files exist and open is {@link TlsConfiguredLoader}'s job, so the
 * failures here are about contradictory or malformed configuration values.
 */
class TlsPathSourceTest {

    @Test
    void isInactiveWhenNothingIsConfigured() {
        TlsPathSource source = new TlsPathSource("", "", "", "", "", "");

        assertThat(source.configured()).isFalse();
        assertThat(source.keystore()).isEmpty();
        assertThat(source.pemCertificate()).isEmpty();
        assertThat(source.httpsPort()).isEqualTo(8443);
        assertThat(source.redirectHttp()).isTrue();
    }

    @Test
    void parsesAKeystorePathAndItsPassphrase() {
        TlsPathSource source = new TlsPathSource("./data/certs/jync.p12", "changeit",
                null, null, "9443", "false");

        assertThat(source.configured()).isTrue();
        assertThat(source.keystore()).contains(
                Paths.get("./data/certs/jync.p12").toAbsolutePath().normalize());
        assertThat(source.keystorePassword()).isEqualTo("changeit");
        assertThat(source.pemCertificate()).isEmpty();
        assertThat(source.httpsPort()).isEqualTo(9443);
        assertThat(source.redirectHttp()).isFalse();
    }

    @Test
    void parsesASeparatePemCertificateAndKey() {
        TlsPathSource source = new TlsPathSource(null, null,
                "/etc/jync/tls/fullchain.pem", "/etc/jync/tls/privkey.pem", null, "yes");

        assertThat(source.configured()).isTrue();
        assertThat(source.keystore()).isEmpty();
        assertThat(source.pemCertificate()).contains(Paths.get("/etc/jync/tls/fullchain.pem"));
        assertThat(source.pemKey()).contains(Paths.get("/etc/jync/tls/privkey.pem"));
        assertThat(source.redirectHttp()).isTrue();
    }

    @Test
    void acceptsACombinedPemBundleWithoutASeparateKey() {
        // HAProxy-style bundles carry the key in the certificate file; the key path is optional.
        TlsPathSource source = new TlsPathSource(null, null,
                "/etc/jync/tls/bundle.pem", "", null, null);

        assertThat(source.configured()).isTrue();
        assertThat(source.pemCertificate()).contains(Paths.get("/etc/jync/tls/bundle.pem"));
        assertThat(source.pemKey()).isEmpty();
    }

    @Test
    void rejectsABothSourcesAtOnce() {
        assertThatThrownBy(() -> new TlsPathSource("/certs/jync.p12", "changeit",
                "/certs/fullchain.pem", "/certs/privkey.pem", null, null))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.configSourceConflict");
    }

    @Test
    void rejectsAPemKeyWithoutItsCertificate() {
        assertThatThrownBy(() -> new TlsPathSource(null, null, "", "/etc/jync/tls/privkey.pem",
                null, null))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.configSourceConflict");
    }

    @Test
    void rejectsAPortOutsideTheRange() {
        assertThatThrownBy(() -> new TlsPathSource("/certs/jync.p12", "changeit", null, null,
                "70000", null))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.portInvalid");
    }

    @Test
    void rejectsANonBooleanRedirectValue() {
        assertThatThrownBy(() -> new TlsPathSource("/certs/jync.p12", "changeit", null, null,
                null, "sometimes"))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.configInvalid");
    }

    @Test
    void toStringDoesNotContainThePassphrase() {
        TlsPathSource source = new TlsPathSource("/certs/jync.p12", "changeit", null, null,
                null, null);

        assertThat(source.toString()).doesNotContain("changeit").contains("jync.p12");
    }
}
