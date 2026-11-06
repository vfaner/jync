package com.qqmu.jync.service.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.util.CryptoUtil;

class TlsRuntimeStateTest {

    @TempDir
    Path dir;

    private TlsKeystoreStore store() {
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword("store-password");
        properties.setCryptoSalt("0123456789abcdef");
        return new TlsKeystoreStore(dir.toString(), new CryptoUtil(properties));
    }

    @Test
    void loadsTheSavedConfigWhenEnabled() {
        TlsKeystoreStore store = store();
        TlsConfig saved = store.save(TestKeystores.pkcs12(), TlsKeystoreFormat.PKCS12,
                TestKeystores.PASSPHRASE, 8443, true);

        TlsRuntimeState state = TlsRuntimeState.load(true, store,
                new TlsCertificateInspector());

        assertThat(state.enabled()).isTrue();
        assertThat(state.problemKey()).isNull();
        assertThat(state.active()).isPresent();
        assertThat(state.active().get().httpsPort()).isEqualTo(8443);
        assertThat(state.active().get().redirectHttp()).isTrue();
        assertThat(state.active().get().updatedAt()).isEqualTo(saved.updatedAt());
        assertThat(state.active().get().sha256()).isEqualTo(saved.sha256());
        assertThat(state.info()).isPresent();
        assertThat(state.info().get().subject()).contains("CN=misconfigured.example");
    }

    @Test
    void isDisabledWithoutTouchingTheStore() {
        TlsKeystoreStore store = mock(TlsKeystoreStore.class);
        TlsCertificateInspector inspector = mock(TlsCertificateInspector.class);

        TlsRuntimeState state = TlsRuntimeState.load(false, store, inspector);

        verifyNoInteractions(store, inspector);
        assertThat(state.enabled()).isFalse();
        assertThat(state.active()).isEmpty();
        assertThat(state.info()).isEmpty();
        assertThat(state.problemKey()).isNull();
    }

    @Test
    void hasNoConfigWhenNothingIsSaved() {
        TlsRuntimeState state = TlsRuntimeState.load(true, store(),
                new TlsCertificateInspector());

        assertThat(state.enabled()).isTrue();
        assertThat(state.active()).isEmpty();
        assertThat(state.problemKey()).isNull();
    }

    @Test
    void failsFastOnACorruptSidecar() {
        TlsKeystoreStore store = mock(TlsKeystoreStore.class);
        when(store.snapshot()).thenReturn(new TlsStoreSnapshot(Optional.empty(),
                "error.tls.storeCorrupt", true, true));

        assertThatThrownBy(() -> TlsRuntimeState.load(true, store,
                new TlsCertificateInspector()))
                .isInstanceOf(TlsKeystoreException.class)
                .satisfies(e -> assertThat(((TlsKeystoreException) e).messageKey())
                        .isEqualTo("error.tls.storeCorrupt"));
    }

    @Test
    void failsFastOnADigestMismatch() {
        TlsKeystoreStore store = mock(TlsKeystoreStore.class);
        when(store.snapshot()).thenReturn(new TlsStoreSnapshot(Optional.empty(),
                "error.tls.keystoreChanged", true, true));

        assertThatThrownBy(() -> TlsRuntimeState.load(true, store,
                new TlsCertificateInspector()))
                .isInstanceOf(TlsKeystoreException.class)
                .satisfies(e -> assertThat(((TlsKeystoreException) e).messageKey())
                        .isEqualTo("error.tls.keystoreChanged"));
    }

    @Test
    void failsFastWhenTheKeyDoesNotOpen() {
        TlsKeystoreStore store = mock(TlsKeystoreStore.class);
        TlsConfig config = new TlsConfig(TlsKeystoreFormat.PKCS12, "keystore-x.p12",
                "changeit", 8443, false, Instant.now(), "00");
        when(store.snapshot()).thenReturn(new TlsStoreSnapshot(Optional.of(config), null,
                true, true));
        when(store.keystoreBytes(any())).thenReturn(TestKeystores.pkcs12());
        TlsCertificateInspector inspector = mock(TlsCertificateInspector.class);
        when(inspector.inspect(any(), any(), any()))
                .thenThrow(new TlsKeystoreException("error.tls.unreadable"));

        assertThatThrownBy(() -> TlsRuntimeState.load(true, store, inspector))
                .isInstanceOf(TlsKeystoreException.class)
                .satisfies(e -> assertThat(((TlsKeystoreException) e).messageKey())
                        .isEqualTo("error.tls.unreadable"));
    }

    @Test
    void logsButServesAnExpiredCertificate() {
        TlsKeystoreStore store = mock(TlsKeystoreStore.class);
        TlsConfig config = new TlsConfig(TlsKeystoreFormat.PKCS12, "keystore-x.p12",
                "changeit", 8443, false, Instant.now(), "00");
        when(store.snapshot()).thenReturn(new TlsStoreSnapshot(Optional.of(config), null,
                true, true));
        when(store.keystoreBytes(any())).thenReturn(TestKeystores.pkcs12());
        TlsCertificateInspector inspector = mock(TlsCertificateInspector.class);
        Instant past = Instant.now().minusSeconds(3600);
        when(inspector.inspect(any(), any(), any())).thenReturn(new TlsCertificateInfo(
                "CN=old.example", "CN=old.example", "old", "RSA", "00".repeat(32),
                past.minusSeconds(86400), past));

        TlsRuntimeState state = TlsRuntimeState.load(true, store, inspector);

        assertThat(state.active()).isPresent();
        assertThat(state.info()).isPresent();
        assertThat(state.info().get().isExpired(Instant.now())).isTrue();
    }

    @Test
    void keepsTheStartupSnapshotAfterTheStoreChanges() {
        TlsKeystoreStore store = store();
        store.save(TestKeystores.pkcs12(), TlsKeystoreFormat.PKCS12,
                TestKeystores.PASSPHRASE, 8443, false);

        TlsRuntimeState state = TlsRuntimeState.load(true, store,
                new TlsCertificateInspector());
        store.delete();

        assertThat(state.active()).isPresent();
        assertThat(store.snapshot().config()).isEmpty();
    }

    @Test
    void toStringDoesNotContainThePassphrase() {
        TlsKeystoreStore store = mock(TlsKeystoreStore.class);
        TlsConfig config = new TlsConfig(TlsKeystoreFormat.PKCS12, "keystore-x.p12",
                "changeit", 8443, false, Instant.now(), "00");
        when(store.snapshot()).thenReturn(new TlsStoreSnapshot(Optional.of(config), null,
                true, true));
        when(store.keystoreBytes(any())).thenReturn(TestKeystores.pkcs12());
        TlsCertificateInspector inspector = mock(TlsCertificateInspector.class);
        Mockito.when(inspector.inspect(any(), any(), any()))
                .thenReturn(new TlsCertificateInfo("CN=a", "CN=a", "a", "RSA",
                        "00".repeat(32), Instant.now(), Instant.now().plusSeconds(3600)));

        TlsRuntimeState state = TlsRuntimeState.load(true, store, inspector);

        assertThat(state.toString()).doesNotContain("changeit");
    }
}
