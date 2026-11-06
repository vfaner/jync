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

    private TlsRuntimeState(boolean enabled, TlsConfig active, TlsCertificateInfo info,
                            String problemKey) {
        this.enabled = enabled;
        this.active = active;
        this.info = info;
        this.problemKey = problemKey;
    }

    public static TlsRuntimeState disabled() {
        return new TlsRuntimeState(false, null, null, null);
    }

    public static TlsRuntimeState load(boolean enabled, TlsKeystoreStore store,
                                       TlsCertificateInspector inspector) {
        if (!enabled) {
            log.info("Application TLS is disabled (app.tls.enabled=false); the embedding "
                    + "container serves HTTP only.");
            return disabled();
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
            return new TlsRuntimeState(true, null, null, null);
        }

        TlsConfig config = snapshot.config().get();
        TlsCertificateInfo certificate;
        try {
            certificate = inspector.inspect(store.keystoreBytes(config), config.format(),
                    config.passphrase().toCharArray());
        } catch (TlsKeystoreException e) {
            log.error("The saved TLS keystore cannot be opened ({}) and the application will "
                    + "not start. Start once with app.tls.enabled=false, re-upload the "
                    + "certificate, then remove the flag and restart.", e.messageKey());
            throw e;
        }

        Instant now = Instant.now();
        if (certificate.isExpired(now)) {
            log.error("The TLS certificate {} expired at {}; HTTPS starts anyway, but browsers "
                    + "will refuse it. Upload a renewed certificate and restart.",
                    certificate.subject(), certificate.notAfter());
        } else if (certificate.isNotYetValid(now)) {
            log.error("The TLS certificate {} is not valid before {}; HTTPS starts anyway, but "
                    + "browsers will refuse it until that instant.",
                    certificate.subject(), certificate.notBefore());
        }

        return new TlsRuntimeState(true, config, certificate, null);
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

    @Override
    public String toString() {
        return "TlsRuntimeState[enabled=" + enabled
                + ", active=" + (active == null ? "<none>" : active.toString())
                + ", problemKey=" + problemKey + "]";
    }
}
