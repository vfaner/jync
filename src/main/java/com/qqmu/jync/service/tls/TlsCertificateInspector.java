package com.qqmu.jync.service.tls;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Opens an uploaded keystore and describes the certificate Tomcat would serve.
 *
 * <p>Tomcat wants exactly one key entry; a store with none or several is rejected here rather
 * than at the next restart, when there is no page left to explain it. The passphrase passed in is
 * the keystore <em>and</em> key passphrase — the single-passphrase model the upload form states.
 */
@Component
public class TlsCertificateInspector {

    public TlsCertificateInfo inspect(byte[] bytes, TlsKeystoreFormat format, char[] passphrase) {
        if (bytes == null || bytes.length == 0) {
            throw new TlsKeystoreException("error.tls.fileRequired");
        }
        KeyStore store;
        try {
            store = KeyStore.getInstance(format.keystoreType());
            store.load(new ByteArrayInputStream(bytes),
                    passphrase == null ? new char[0] : passphrase);
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new TlsKeystoreException("error.tls.unreadable", e);
        }

        List<String> keyAliases = keyAliases(store);
        if (keyAliases.isEmpty()) {
            throw new TlsKeystoreException("error.tls.noPrivateKey");
        }
        if (keyAliases.size() > 1) {
            throw new TlsKeystoreException("error.tls.multipleKeys");
        }

        String alias = keyAliases.get(0);
        Key key;
        Certificate[] chain;
        try {
            key = store.getKey(alias, passphrase == null ? new char[0] : passphrase);
            chain = store.getCertificateChain(alias);
        } catch (GeneralSecurityException e) {
            throw new TlsKeystoreException("error.tls.unreadable", e);
        }
        if (key == null || chain == null || chain.length == 0
                || !(chain[0] instanceof X509Certificate)) {
            throw new TlsKeystoreException("error.tls.unreadable");
        }

        X509Certificate leaf = (X509Certificate) chain[0];
        // A key entry can quote a certificate that belongs to another key. The PEM path
        // checks this; a keystore has the same failure mode, and Tomcat would only discover
        // it at the first handshake after the restart.
        if (key instanceof java.security.PrivateKey privateKey) {
            PemKeyPairLoader.verifyKeyMatches(leaf, privateKey, "error.tls.keyMismatch",
                    "error.tls.unreadable");
        }
        return new TlsCertificateInfo(
                leaf.getSubjectX500Principal().getName(),
                leaf.getIssuerX500Principal().getName(),
                alias,
                leaf.getPublicKey().getAlgorithm(),
                sha256Hex(leaf),
                leaf.getNotBefore().toInstant(),
                leaf.getNotAfter().toInstant());
    }

    private static List<String> keyAliases(KeyStore store) {
        List<String> aliases = new ArrayList<>();
        try {
            for (String alias : Collections.list(store.aliases())) {
                if (store.isKeyEntry(alias)) {
                    aliases.add(alias);
                }
            }
        } catch (GeneralSecurityException e) {
            throw new TlsKeystoreException("error.tls.unreadable", e);
        }
        return aliases;
    }

    private static String sha256Hex(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | java.security.cert.CertificateEncodingException e) {
            throw new TlsKeystoreException("error.tls.unreadable", e);
        }
    }

}
