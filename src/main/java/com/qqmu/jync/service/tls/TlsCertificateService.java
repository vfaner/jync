package com.qqmu.jync.service.tls;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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

    /** Passphrases for PEM uploads are generated, not typed; one source of randomness. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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

        return validateAndStore(bytes, format, passphrase.toCharArray(), httpsPort, redirectHttp);
    }

    /**
     * Saves an nginx-style certificate chain plus its unencrypted private key.
     *
     * <p>PEM is what operators already have; Tomcat only reads keystores. The pair is wrapped in a
     * PKCS#12 in memory under a passphrase generated here, so the plaintext key is never written to
     * disk and the page needs no second passphrase field. {@code privateKeyPem} may be empty when
     * the certificate file is a combined bundle that already carries the key. The rest of the
     * gate — dates, port, probe — is shared with the keystore upload.
     */
    public TlsConfig savePem(byte[] certificatePem, byte[] privateKeyPem, int httpsPort,
                             boolean redirectHttp) {
        if (certificatePem == null || certificatePem.length == 0) {
            throw new TlsKeystoreException("error.tls.pemCertRequired");
        }
        boolean separateKey = privateKeyPem != null && privateKeyPem.length > 0;
        if (certificatePem.length > MAX_BYTES
                || (separateKey && privateKeyPem.length > MAX_BYTES)) {
            throw new TlsKeystoreException("error.tls.fileTooLarge");
        }

        PemKeyPair pair = separateKey
                ? PemKeyPairLoader.load(certificatePem, privateKeyPem)
                : PemKeyPairLoader.load(certificatePem);
        char[] secret = randomPassphrase();
        byte[] keystore = pair.toPkcs12(secret);
        return validateAndStore(keystore, TlsKeystoreFormat.PKCS12, secret, httpsPort,
                redirectHttp);
    }

    private TlsConfig validateAndStore(byte[] bytes, TlsKeystoreFormat format, char[] secret,
                                       int httpsPort, boolean redirectHttp) {
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

    /** 32 URL-safe characters — long enough that the keystore is not guessable. */
    private static char[] randomPassphrase() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
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
                // Removal pending: the files are gone but the running connector still redirects,
                // so the card has to describe that connector rather than a default.
                saved.map(TlsConfig::redirectHttp)
                        .orElse(active.map(TlsConfig::redirectHttp).orElse(false)),
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
