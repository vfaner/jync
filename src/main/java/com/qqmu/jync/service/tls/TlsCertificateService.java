package com.qqmu.jync.service.tls;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.qqmu.jync.util.CryptoUtil;

/**
 * The upload gate in front of {@link TlsKeystoreStore}, and the page's view of it.
 *
 * <p>Every rejection here happens before anything is written: a keystore the next boot cannot
 * open would turn a typo in the passphrase into an instance that refuses to start. The order is
 * cheap check first (size, extension, passphrase length), then the expensive ones (opening the
 * store), then the port, which is the only check that can be satisfied now and false at the next
 * boot anyway.
 *
 * <p>The certificate is validated but its chain is not: this page installs a certificate the
 * operator obtained elsewhere, and the browsers are the ones that decide whether to trust it.
 */
@Service
public class TlsCertificateService {

    /** Tomcat's default multipart ceiling is applied again in the controller; this is the
     * same number in one place. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    /** Long enough for any real passphrase, short enough to bound a password encoder call. */
    static final int MAX_PASSPHRASE = 1024;

    /** How early the page starts warning that the certificate is about to expire. */
    static final Duration EXPIRY_WARNING = Duration.ofDays(30);

    private final TlsKeystoreStore store;
    private final TlsCertificateInspector inspector;
    private final TlsRuntimeState runtime;
    private final CryptoUtil crypto;
    private final int httpPort;

    public TlsCertificateService(TlsKeystoreStore store,
                                 TlsCertificateInspector inspector,
                                 TlsRuntimeState runtime,
                                 CryptoUtil crypto,
                                 @Value("${server.port:8080}") int httpPort) {
        this.store = store;
        this.inspector = inspector;
        this.runtime = runtime;
        this.crypto = crypto;
        this.httpPort = httpPort;
    }

    /**
     * Validates an uploaded keystore and saves it.
     *
     * @throws TlsKeystoreException with an {@code error.tls.*} key; nothing was written
     */
    public TlsConfig save(byte[] bytes, String filename, String passphrase, int httpsPort,
                          boolean redirectHttp) {
        if (bytes == null || bytes.length == 0) {
            throw new TlsKeystoreException("error.tls.fileRequired");
        }
        if (bytes.length > MAX_BYTES) {
            throw new TlsKeystoreException("error.tls.fileTooLarge");
        }
        TlsKeystoreFormat format = TlsKeystoreFormat.fromFilename(filename)
                .orElseThrow(() -> new TlsKeystoreException("error.tls.unsupportedFormat"));
        if (passphrase == null || passphrase.isEmpty()) {
            throw new TlsKeystoreException("error.tls.passphraseRequired");
        }
        if (passphrase.length() > MAX_PASSPHRASE) {
            throw new TlsKeystoreException("error.tls.passphraseTooLong");
        }

        char[] secret = passphrase.toCharArray();
        TlsCertificateInfo info = inspector.inspect(bytes, format, secret);
        Instant now = Instant.now();
        if (info.isExpired(now)) {
            throw new TlsKeystoreException("error.tls.expired");
        }
        if (info.isNotYetValid(now)) {
            throw new TlsKeystoreException("error.tls.notYetValid");
        }

        if (httpsPort < 1 || httpsPort > 65535) {
            throw new TlsKeystoreException("error.tls.portInvalid");
        }
        if (httpsPort == httpPort) {
            throw new TlsKeystoreException("error.tls.portEqualsHttp");
        }
        // The connector that already owns the port is the running one; probing it would refuse
        // the very re-upload that is meant to replace its certificate at the next restart.
        boolean isTheActivePort = runtime.active()
                .map(active -> active.httpsPort() == httpsPort)
                .orElse(false);
        if (!isTheActivePort) {
            requireFreePort(httpsPort);
        }

        return store.save(bytes, format, secret, httpsPort, redirectHttp);
    }

    /** @return whether anything was removed */
    public boolean delete() {
        return store.delete();
    }

    public TlsPageState pageState() {
        TlsStoreSnapshot snapshot = store.snapshot();
        Optional<TlsConfig> saved = snapshot.config();
        Optional<TlsConfig> active = runtime.active();

        TlsCertificateInfo certificate = saved.map(this::inspectOrNull).orElse(null);
        TlsCertificateInfo activeCertificate = active.map(this::inspectOrNull).orElse(null);
        String problemKey = snapshot.problemKey();
        if (problemKey == null && saved.isPresent() && certificate == null) {
            problemKey = "error.tls.unreadable";
        }

        boolean savedPresent = saved.isPresent();
        boolean activePresent = active.isPresent();
        boolean pendingRestart = savedPresent != activePresent
                || (savedPresent && !sameConnector(saved.get(), active.get()));
        boolean canRemove = savedPresent || snapshot.sidecarPresent()
                || snapshot.keystorePresent();

        // The chip describes the certificate the page is showing: the saved one when there is
        // one, otherwise whatever the running connector is still serving.
        TlsCertificateInfo shown = certificate != null ? certificate : activeCertificate;
        Instant now = Instant.now();
        boolean expired = shown != null && shown.isExpired(now);
        boolean expiresSoon = shown != null && shown.expiresWithin(now, EXPIRY_WARNING);

        return new TlsPageState(
                savedPresent,
                activePresent,
                pendingRestart,
                !savedPresent && activePresent,
                !runtime.enabled(),
                canRemove,
                crypto.isUsingDefaultKey(),
                problemKey,
                certificate,
                activeCertificate,
                expired,
                expiresSoon,
                saved.map(TlsConfig::httpsPort).orElse(active.map(TlsConfig::httpsPort).orElse(null)),
                saved.map(TlsConfig::redirectHttp).orElse(false),
                saved.map(TlsConfig::updatedAt).orElse(null));
    }

    /** Whether the saved configuration describes the connector that is actually listening. */
    private static boolean sameConnector(TlsConfig saved, TlsConfig active) {
        return saved.format() == active.format()
                && Objects.equals(saved.sha256(), active.sha256())
                && saved.httpsPort() == active.httpsPort()
                && saved.redirectHttp() == active.redirectHttp();
    }

    /** A page whose saved keystore cannot be opened still names every other fact. */
    private TlsCertificateInfo inspectOrNull(TlsConfig config) {
        try {
            return inspector.inspect(store.keystoreBytes(config), config.format(),
                    config.passphrase().toCharArray());
        } catch (TlsKeystoreException e) {
            return null;
        }
    }

    private static void requireFreePort(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            throw new TlsKeystoreException("error.tls.portInUse", e);
        }
    }
}
