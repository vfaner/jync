package com.qqmu.jync.service.tls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TlsKeystoreFormatTest {

    @Test
    void recognizesP12PfxAndJks() {
        assertThat(TlsKeystoreFormat.fromFilename("server.p12")).contains(TlsKeystoreFormat.PKCS12);
        assertThat(TlsKeystoreFormat.fromFilename("SERVER.PFX")).contains(TlsKeystoreFormat.PKCS12);
        assertThat(TlsKeystoreFormat.fromFilename("server.jks")).contains(TlsKeystoreFormat.JKS);
    }

    @Test
    void rejectsOtherExtensions() {
        assertThat(TlsKeystoreFormat.fromFilename("server.pem")).isEmpty();
        assertThat(TlsKeystoreFormat.fromFilename("server.p12.txt")).isEmpty();
        assertThat(TlsKeystoreFormat.fromFilename("server")).isEmpty();
        assertThat(TlsKeystoreFormat.fromFilename(null)).isEmpty();
    }

    @Test
    void mapsToTheJdkKeystoreTypeAndASafeSavedExtension() {
        assertThat(TlsKeystoreFormat.PKCS12.keystoreType()).isEqualTo("PKCS12");
        assertThat(TlsKeystoreFormat.PKCS12.extension()).isEqualTo("p12");
        assertThat(TlsKeystoreFormat.JKS.keystoreType()).isEqualTo("JKS");
        assertThat(TlsKeystoreFormat.JKS.extension()).isEqualTo("jks");
    }
}
