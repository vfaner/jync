package com.qqmu.jync.service.tls;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The certificate the running process was started with.
 *
 * <p>Built once, during {@code ServletWebServerApplicationContext.onRefresh()} — before the
 * DataSource exists — so it depends only on the keystore store, the inspector and the
 * filesystem. It is a snapshot by design: the page can save or remove files afterwards, but the
 * connector keeps serving whatever was loaded here until the next restart.
 *
 * <p>A broken saved configuration fails the boot. Serving with the wrong certificate is worse
 * than not serving at all, and {@code app.tls.enabled=false} is the documented way back in to
 * repair it. An expired certificate is different: HTTPS still works, browsers warn, and the page
 * says so.
 */
public class TlsRuntimeState {

    private static final Logger log = LoggerFactory.getLogger(TlsRuntimeState.class);

    private final boolean enabled;
    private final TlsConfig active;
    private final TlsCertificateInfo info;
    private final String problemKey;
    private final TlsConfiguredPaths configuredPaths;

    private TlsRuntimeState(boolean enabled, TlsConfig active, TlsCertificateInfo info,
                            String problemKey, TlsConfiguredPaths configuredPaths) {
        this.enabled = enabled;
        this.active = active;
        this.info = info;
        this.problemKey = problemKey;
        this.configuredPaths = configuredPaths;
    }

    public static TlsRuntimeState disabled() {
        return new TlsRuntimeState(false, null, null, null, null);
    }

    public static TlsRuntimeState load(boolean enabled, TlsKeystoreStore store,
                                       TlsCertificateInspector inspector) {
        return load(enabled, store, inspector, TlsConfiguredLoader.none());
    }

    /**
     * Startup load that prefers the certificate named in the configuration file.
     *
     * <p>A configured source is the operator's override: it wins over anything uploaded to the
     * page, and the saved sidecar is not even read. It still cannot win over
     * {@code app.tls.enabled=false} — that flag exists to bring a broken instance back.
     */
    public static TlsRuntimeState load(boolean enabled, TlsKeystoreStore store,
                                       TlsCertificateInspector inspector,
                                       TlsConfiguredLoader configuredLoader) {
        if (!enabled) {
            log.info("Application TLS is disabled (app.tls.enabled=false); the embedding "
                    + "container serves HTTP only.");
            return disabled();
        }

        Optional<TlsConfigured> configured = configuredLoader.load();
        if (configured.isPresent()) {
            TlsConfig config = configured.get().config();
            TlsCertificateInfo certificate = inspectOrFail(config, store, inspector,
                    "The TLS certificate named in the configuration file cannot be opened");
            logValidity(certificate);
            return new TlsRuntimeState(true, config, certificate, null,
                    configured.get().paths());
        }

        TlsStoreSnapshot snapshot = store.snapshot();
        if (snapshot.problemKey() != null) {
            log.error("The saved TLS configuration cannot be used ({}) and the application "
                    + "will not start. Start once with app.tls.enabled=false, remove or re-upload "
                    + "the certificate on the system configuration page, then remove the flag "
                    + "and restart.",
                    snapshot.problemKey());
            throw new TlsKeystoreException(snapshot.problemKey());
        }
        if (snapshot.config().isEmpty()) {
            return new TlsRuntimeState(true, null, null, null, null);
        }

        TlsConfig config = snapshot.config().get();
        TlsCertificateInfo certificate = inspectOrFail(config, store, inspector,
                "The saved TLS keystore cannot be opened");
        logValidity(certificate);

        return new TlsRuntimeState(true, config, certificate, null, null);
    }

    private static TlsCertificateInfo inspectOrFail(TlsConfig config, TlsKeystoreStore store,
                                                    TlsCertificateInspector inspector,
                                                    String failureHint) {
        try {
            return inspector.inspect(store.keystoreBytes(config), config.format(),
                    config.passphrase().toCharArray());
        } catch (TlsKeystoreException e) {
            log.error("{} ({}) and the application will not start. Start once with "
                    + "app.tls.enabled=false, repair the certificate, then remove the flag and "
                    + "restart.", failureHint, e.messageKey());
            throw e;
        }
    }

    private static void logValidity(TlsCertificateInfo certificate) {
        Instant now = Instant.now();
        if (certificate.isExpired(now)) {
            log.error("The TLS certificate {} expired at {}; HTTPS starts anyway, but browsers "
                    + "will refuse it. Upload or configure a renewed certificate and restart.",
                    certificate.subject(), certificate.notAfter());
        } else if (certificate.isNotYetValid(now)) {
            log.error("The TLS certificate {} is not valid before {}; HTTPS starts anyway, but "
                    + "browsers will refuse it until that instant.",
                    certificate.subject(), certificate.notBefore());
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<TlsConfig> active() {
        return Optional.ofNullable(active);
    }

    public Optional<TlsCertificateInfo> info() {
        return Optional.ofNullable(info);
    }

    public String problemKey() {
        return problemKey;
    }

    /** Present when the running certificate came from {@code app.tls.*} paths. */
    public Optional<TlsConfiguredPaths> configuredPaths() {
        return Optional.ofNullable(configuredPaths);
    }

    @Override
    public String toString() {
        return "TlsRuntimeState[enabled=" + enabled
                + ", active=" + (active == null ? "<none>" : active.toString())
                + ", problemKey=" + problemKey + "]";
    }
}
