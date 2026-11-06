package com.qqmu.jync.service.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.util.CryptoUtil;

/**
 * The upload gate and the page's view of what is saved versus what is running.
 *
 * <p>The service is handed bytes, not a multipart file, so the web layer owns only the
 * translation from the form. Every rejection happens before the store writes anything: an
 * unusable certificate that reached the disk would fail the next boot.
 */
class TlsCertificateServiceTest {

    @TempDir
    Path dir;

    private TlsKeystoreStore store;
    private TlsCertificateInspector inspector;
    private TlsRuntimeState runtime;
    private CryptoUtil crypto;
    /** Ephemeral, because the developer's own instance may already hold 8443. */
    private int httpsPort;

    @BeforeEach
    void setUp() throws IOException {
        crypto = new CryptoUtil(properties());
        store = new TlsKeystoreStore(dir.toString(), crypto);
        inspector = new TlsCertificateInspector();
        runtime = runtimeWith(Optional.empty());
        try (ServerSocket socket = new ServerSocket(0)) {
            httpsPort = socket.getLocalPort();
        }
    }

    private static SyncProperties properties() {
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword("store-password");
        properties.setCryptoSalt("0123456789abcdef");
        return properties;
    }

    private TlsCertificateService service() {
        return new TlsCertificateService(store, inspector, runtime, crypto, 8080);
    }

    private static TlsRuntimeState runtimeWith(Optional<TlsConfig> active) {
        TlsRuntimeState state = Mockito.mock(TlsRuntimeState.class);
        Mockito.when(state.enabled()).thenReturn(true);
        Mockito.when(state.active()).thenReturn(active);
        return state;
    }

    private void save() {
        service().save(TestKeystores.pkcs12(), "certificate.p12", "changeit", httpsPort, true);
    }

    // ── Rejections ─────────────────────────────────────────────────────────────

    @Test
    void rejectsAnEmptyFile() {
        assertThatThrownBy(() -> service().save(new byte[0], "certificate.p12", "changeit", httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.fileRequired");
    }

    @Test
    void rejectsAnOversizedFile() {
        byte[] tooLarge = new byte[TlsCertificateService.MAX_BYTES + 1];

        assertThatThrownBy(() -> service().save(tooLarge, "certificate.p12", "changeit", httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.fileTooLarge");
    }

    @Test
    void rejectsAnUnsupportedFormat() {
        assertThatThrownBy(() -> service().save(TestKeystores.pkcs12(), "certificate.pem",
                "changeit", httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.unsupportedFormat");
    }

    @Test
    void rejectsAnEmptyPassphrase() {
        assertThatThrownBy(() -> service().save(TestKeystores.pkcs12(), "certificate.p12", "",
                httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.passphraseRequired");
    }

    @Test
    void rejectsAnOversizedPassphrase() {
        String tooLong = "x".repeat(TlsCertificateService.MAX_PASSPHRASE + 1);

        assertThatThrownBy(() -> service().save(TestKeystores.pkcs12(), "certificate.p12",
                tooLong, httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.passphraseTooLong");
    }

    @Test
    void rejectsTheWrongPassphrase() {
        assertThatThrownBy(() -> service().save(TestKeystores.pkcs12(), "certificate.p12",
                "wrong-passphrase", httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.unreadable");
        assertThat(Files.exists(dir.resolve(TlsKeystoreStore.SIDECAR))).isFalse();
    }

    @Test
    void rejectsAStoreWithoutAPrivateKey() throws Exception {
        KeyStore empty = KeyStore.getInstance("PKCS12");
        empty.load(null, null);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        empty.store(bytes, TestKeystores.PASSPHRASE);

        assertThatThrownBy(() -> service().save(bytes.toByteArray(), "truststore.p12",
                "changeit", httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.noPrivateKey");
    }

    @Test
    void rejectsAnExpiredCertificate() throws Exception {
        TlsCertificateInspector mockInspector = Mockito.mock(TlsCertificateInspector.class);
        Mockito.when(mockInspector.inspect(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(info(-3600, 0));
        TlsCertificateService service = new TlsCertificateService(store, mockInspector,
                runtime, crypto, 8080);

        assertThatThrownBy(() -> service.save(TestKeystores.pkcs12(), "certificate.p12",
                "changeit", httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.expired");
        assertThat(Files.exists(dir.resolve(TlsKeystoreStore.SIDECAR))).isFalse();
    }

    @Test
    void rejectsANotYetValidCertificate() {
        TlsCertificateInspector mockInspector = Mockito.mock(TlsCertificateInspector.class);
        Mockito.when(mockInspector.inspect(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(info(3600, 7200));
        TlsCertificateService service = new TlsCertificateService(store, mockInspector,
                runtime, crypto, 8080);

        assertThatThrownBy(() -> service.save(TestKeystores.pkcs12(), "certificate.p12",
                "changeit", httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.notYetValid");
    }

    @Test
    void rejectsAPortOutsideTheRange() {
        assertThatThrownBy(() -> service().save(TestKeystores.pkcs12(), "certificate.p12",
                "changeit", 70000, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.portInvalid");
    }

    @Test
    void rejectsTheHttpPort() {
        assertThatThrownBy(() -> service().save(TestKeystores.pkcs12(), "certificate.p12",
                "changeit", 8080, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.portEqualsHttp");
    }

    @Test
    void rejectsAPortInUse() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            assertThatThrownBy(() -> service().save(TestKeystores.pkcs12(), "certificate.p12",
                    "changeit", socket.getLocalPort(), false))
                    .isInstanceOf(TlsKeystoreException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "error.tls.portInUse");
        }
    }

    @Test
    void allowsReuploadingToTheActiveHttpsPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            // The running connector already owns this port; probing it would refuse every save.
            runtime = runtimeWith(Optional.of(config(socket.getLocalPort())));

            assertThat(service().save(TestKeystores.pkcs12(), "certificate.p12", "changeit",
                    socket.getLocalPort(), false).httpsPort())
                    .isEqualTo(socket.getLocalPort());
        }
    }

    // ── Page state ─────────────────────────────────────────────────────────────

    @Test
    void saveThenPageStateShowsPendingRestart() {
        save();

        TlsPageState state = service().pageState();

        assertThat(state.saved()).isTrue();
        assertThat(state.active()).isFalse();
        assertThat(state.pendingRestart()).isTrue();
        assertThat(state.certificate()).isNotNull();
        assertThat(state.certificate().subject()).contains("CN=misconfigured.example");
        assertThat(state.canRemove()).isTrue();
    }

    @Test
    void pageStateShowsSavedAndActiveWhenTheyMatch() {
        save();
        runtime = TlsRuntimeState.load(true, store, inspector);

        TlsPageState state = service().pageState();

        assertThat(state.saved()).isTrue();
        assertThat(state.active()).isTrue();
        assertThat(state.pendingRestart()).isFalse();
        assertThat(state.activeCertificate()).isNotNull();
        assertThat(state.httpsPort()).isEqualTo(httpsPort);
        assertThat(state.redirectHttp()).isTrue();
    }

    @Test
    void pageStateShowsPendingRestartWhenOnlyThePortChanged() {
        save();
        TlsConfig running = store.requireConfig();
        runtime = runtimeWith(Optional.of(new TlsConfig(running.format(), running.file(),
                running.passphrase(), 9443, running.redirectHttp(), running.updatedAt(),
                running.sha256())));

        assertThat(service().pageState().pendingRestart()).isTrue();
    }

    @Test
    void pageStateShowsRemovalPending() {
        save();
        runtime = TlsRuntimeState.load(true, store, inspector);
        store.delete();

        TlsPageState state = service().pageState();

        assertThat(state.saved()).isFalse();
        assertThat(state.active()).isTrue();
        assertThat(state.removalPending()).isTrue();
        // The card describes the connector that is still redirecting, not the deleted file.
        assertThat(state.redirectHttp()).isTrue();
        assertThat(state.httpsPort()).isEqualTo(httpsPort);
    }

    @Test
    void deleteRemovesTheConfiguration() {
        save();

        TlsCertificateService service = service();
        assertThat(service.delete()).isTrue();
        assertThat(service.delete()).isFalse();
        assertThat(service.pageState().saved()).isFalse();
    }

    @Test
    void pageStateShowsACorruptSidecarProblemAndOffersRemoval() throws IOException {
        Files.writeString(dir.resolve(TlsKeystoreStore.SIDECAR), "not=this-format\n",
                StandardCharsets.UTF_8);

        TlsPageState state = service().pageState();

        assertThat(state.saved()).isFalse();
        assertThat(state.problemKey()).isEqualTo("error.tls.storeCorrupt");
        assertThat(state.canRemove()).isTrue();
    }

    @Test
    void pageStateWarnsWhenTheDefaultCryptoKeyIsInUse() {
        crypto = new CryptoUtil(new SyncProperties());
        store = new TlsKeystoreStore(dir.toString(), crypto);

        assertThat(service().pageState().usingDefaultCryptoKey()).isTrue();
    }

    @Test
    void pageStateNeverContainsThePassphrase() {
        save();

        assertThat(service().pageState().toString()).doesNotContain("changeit");
    }

    @Test
    void pageStateReportsWhenTlsIsDisabled() {
        Mockito.when(runtime.enabled()).thenReturn(false);

        assertThat(service().pageState().disabled()).isTrue();
    }

    // ── PEM upload ─────────────────────────────────────────────────────────────

    @Test
    void savePemStoresAPkcs12ThatOpensWithTheGeneratedPassphrase() {
        TlsConfig saved = service().savePem(bytes(TestKeystores.certificatePem()),
                bytes(TestKeystores.privateKeyPem()), httpsPort, true);

        assertThat(saved.format()).isEqualTo(TlsKeystoreFormat.PKCS12);
        assertThat(saved.passphrase()).isNotEmpty();
        TlsCertificateInfo info = inspector.inspect(store.keystoreBytes(saved),
                TlsKeystoreFormat.PKCS12, saved.passphrase().toCharArray());
        assertThat(info.subject()).contains("CN=misconfigured.example");
        assertThat(info.notAfter()).isAfter(Instant.parse("2100-01-01T00:00:00Z"));
    }

    @Test
    void savePemAcceptsACombinedCertificateAndKeyFile() {
        // HAProxy-style bundles put the leaf, the chain and the key in one file; the page's
        // second key input is then redundant and may be left empty.
        String combined = TestKeystores.certificatePem() + TestKeystores.privateKeyPem();

        TlsConfig saved = service().savePem(bytes(combined), null, httpsPort, true);

        TlsCertificateInfo info = inspector.inspect(store.keystoreBytes(saved),
                TlsKeystoreFormat.PKCS12, saved.passphrase().toCharArray());
        assertThat(info.subject()).contains("CN=misconfigured.example");
    }

    @Test
    void savePemRejectsACombinedFileWithoutAPrivateKey() {
        assertThatThrownBy(() -> service().savePem(bytes(TestKeystores.certificatePem()), null,
                httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemKeyInvalid");
        assertThat(store.snapshot().config()).isEmpty();
    }

    @Test
    void savePemRequiresACertificate() {
        assertThatThrownBy(() -> service().savePem(new byte[0], null, httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemCertRequired");
    }

    @Test
    void savePemDoesNotLeaveThePrivateKeyOrThePassphraseInPlaintext() throws IOException {
        TlsConfig saved = service().savePem(bytes(TestKeystores.certificatePem()),
                bytes(TestKeystores.privateKeyPem()), httpsPort, false);

        try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = new String(Files.readAllBytes(file),
                        StandardCharsets.ISO_8859_1);
                assertThat(content).as("file %s", file)
                        .doesNotContain("BEGIN PRIVATE KEY", "BEGIN RSA PRIVATE KEY",
                                saved.passphrase());
            }
        }
    }

    @Test
    void savePemRejectsAMismatchedKeyBeforeWritingAnything() {
        assertThatThrownBy(() -> service().savePem(bytes(TestKeystores.certificatePem()),
                bytes(TestKeystores.unrelatedPrivateKeyPem()), httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.pemKeyMismatch");

        assertThat(store.snapshot().config()).isEmpty();
    }

    @Test
    void savePemAppliesTheSamePortRulesAsAKeystoreUpload() {
        assertThatThrownBy(() -> service().savePem(bytes(TestKeystores.certificatePem()),
                bytes(TestKeystores.privateKeyPem()), 8080, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.portEqualsHttp");
    }

    @Test
    void savePemRejectsAnOversizedCertificateOrKey() {
        byte[] cert = bytes(TestKeystores.certificatePem());
        byte[] key = bytes(TestKeystores.privateKeyPem());

        assertThatThrownBy(() -> service().savePem(new byte[TlsCertificateService.MAX_BYTES + 1],
                key, httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.fileTooLarge");
        assertThatThrownBy(() -> service().savePem(cert,
                new byte[TlsCertificateService.MAX_BYTES + 1], httpsPort, false))
                .isInstanceOf(TlsKeystoreException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.tls.fileTooLarge");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static TlsCertificateInfo info(long notBeforeOffsetSeconds, long notAfterOffsetSeconds) {
        Instant now = Instant.now();
        return new TlsCertificateInfo("CN=test", "CN=test", "alias", "RSA",
                "00", now.plusSeconds(notBeforeOffsetSeconds),
                now.plusSeconds(notAfterOffsetSeconds));
    }

    private static TlsConfig config(int httpsPort) {
        return new TlsConfig(TlsKeystoreFormat.PKCS12, "keystore-x.p12", "changeit", httpsPort,
                false, Instant.now(), "00");
    }
}
