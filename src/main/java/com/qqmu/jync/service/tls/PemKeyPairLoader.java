package com.qqmu.jync.service.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Reads nginx-style PEM material: a certificate chain file and an unencrypted private key file.
 *
 * <p>Supported key encodings are PKCS#8 ({@code BEGIN PRIVATE KEY}, what OpenSSL and certbot write
 * today) and PKCS#1 ({@code BEGIN RSA PRIVATE KEY}, what older {@code openssl genrsa} wrote).
 * Encrypted PKCS#8 is rejected with its own key: the form has no passphrase field for PEM and
 * silently guessing an empty one would only fail later, at the next restart.
 *
 * <p>The key is checked against the leaf certificate here by signing and verifying a probe. Without
 * this, a private key pasted from the wrong host would save cleanly and then make Tomcat refuse to
 * start — the one failure this page exists to prevent.
 */
final class PemKeyPairLoader {

    /** Algorithms a Java 17 Tomcat connector can serve, in the order we try to parse them. */
    private static final List<String> KEY_ALGORITHMS =
            List.of("RSA", "EC", "Ed25519", "Ed448", "DSA");

    private static final byte[] KEY_CHECK_PROBE =
            "jync-tls-key-check".getBytes(StandardCharsets.US_ASCII);

    private PemKeyPairLoader() {
    }

    static PemKeyPair load(byte[] certificatePem, byte[] privateKeyPem) {
        return match(readChain(certificatePem), readPrivateKey(privateKeyPem));
    }

    /**
     * One bundle carrying both the chain and the key, as HAProxy and several CA downloads ship
     * them. The two blocks are read from the same text; order between a key block and the
     * certificate blocks does not matter.
     */
    static PemKeyPair load(byte[] pem) {
        return match(readChain(pem), readPrivateKey(pem));
    }

    private static PemKeyPair match(List<X509Certificate> chain, PrivateKey key) {
        requireMatching(chain.get(0), key);
        return new PemKeyPair(chain.toArray(new X509Certificate[0]), key);
    }

    private static List<X509Certificate> readChain(byte[] pem) {
        if (pem == null || pem.length == 0) {
            throw new TlsKeystoreException("error.tls.pemCertInvalid");
        }
        // CertificateFactory.generateCertificates() chokes on a combined bundle as soon as it
        // reaches the private-key block. Feed it one certificate block at a time instead, which
        // also keeps the file order that TLS chain building depends on.
        List<X509Certificate> chain = new ArrayList<>();
        String text = new String(pem, StandardCharsets.US_ASCII);
        for (PemBlock block : readBlocks(text)) {
            if (!"CERTIFICATE".equals(block.type()) && !"X509 CERTIFICATE".equals(block.type())) {
                continue;
            }
            try {
                Certificate certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(
                                block.text().getBytes(StandardCharsets.US_ASCII)));
                if (!(certificate instanceof X509Certificate x509)) {
                    throw new TlsKeystoreException("error.tls.pemCertInvalid");
                }
                chain.add(x509);
            } catch (CertificateException e) {
                throw new TlsKeystoreException("error.tls.pemCertInvalid", e);
            }
        }
        if (chain.isEmpty()) {
            if (text.contains("-----BEGIN ")) {
                // A PEM file that carries only a key, or no usable certificate block.
                throw new TlsKeystoreException("error.tls.pemCertInvalid");
            }
            // Browsers and Windows CAs export ".cer" as bare DER (sometimes PKCS#7).
            return readDerChain(pem);
        }
        return chain;
    }

    private static List<X509Certificate> readDerChain(byte[] der) {
        try {
            Collection<? extends Certificate> certificates =
                    CertificateFactory.getInstance("X.509")
                            .generateCertificates(new ByteArrayInputStream(der));
            if (certificates.isEmpty()) {
                throw new TlsKeystoreException("error.tls.pemCertInvalid");
            }
            List<X509Certificate> chain = new ArrayList<>();
            for (Certificate certificate : certificates) {
                if (!(certificate instanceof X509Certificate x509)) {
                    throw new TlsKeystoreException("error.tls.pemCertInvalid");
                }
                chain.add(x509);
            }
            return chain;
        } catch (CertificateException e) {
            throw new TlsKeystoreException("error.tls.pemCertInvalid", e);
        }
    }

    private static PrivateKey readPrivateKey(byte[] pem) {
        if (pem == null || pem.length == 0) {
            throw new TlsKeystoreException("error.tls.pemKeyInvalid");
        }
        for (PemBlock block : readBlocks(new String(pem, StandardCharsets.US_ASCII))) {
            switch (block.type()) {
                case "ENCRYPTED PRIVATE KEY" ->
                        throw new TlsKeystoreException("error.tls.pemKeyEncrypted");
                case "PRIVATE KEY" -> {
                    return decodePkcs8(decodeBase64(block.body()));
                }
                case "RSA PRIVATE KEY" -> {
                    return decodePkcs1(decodeBase64(block.body()));
                }
                case "EC PRIVATE KEY", "DSA PRIVATE KEY" ->
                        // SEC1 / legacy formats name no algorithm this JDK can load without
                        // parameters; the operator converts them with one openssl command
                        // (the page says which).
                        throw new TlsKeystoreException("error.tls.pemKeyUnsupported");
                default -> {
                    // Some other PEM object (certificate, parameters): not the key.
                }
            }
        }
        throw new TlsKeystoreException("error.tls.pemKeyInvalid");
    }

    /** One PEM block: its label, the whole BEGIN..END text, and the body between them. */
    private record PemBlock(String type, String text, String body) {
    }

    /**
     * Splits PEM text into blocks by scanning forward once.
     *
     * <p>The obvious regex ({@code -----BEGIN (X)-----.*?-----END \1-----}) is quadratic when the
     * input carries many BEGIN markers and no matching END: each start position rescans the rest
     * of the file. A 5 MB upload is allowed, and that shape of file took tens of minutes, so the
     * scan here consumes each block whole and stops at the first truncated one.
     */
    private static List<PemBlock> readBlocks(String text) {
        List<PemBlock> blocks = new ArrayList<>();
        int from = 0;
        while (true) {
            int begin = text.indexOf("-----BEGIN ", from);
            if (begin < 0) {
                return blocks;
            }
            int labelStart = begin + "-----BEGIN ".length();
            int labelEnd = text.indexOf("-----", labelStart);
            if (labelEnd < 0) {
                return blocks;
            }
            String type = text.substring(labelStart, labelEnd);
            String endMarker = "-----END " + type + "-----";
            int end = text.indexOf(endMarker, labelEnd + "-----".length());
            if (end < 0) {
                return blocks;
            }
            blocks.add(new PemBlock(type, text.substring(begin, end + endMarker.length()),
                    text.substring(labelEnd + "-----".length(), end)));
            from = end + endMarker.length();
        }
    }

    private static byte[] decodeBase64(String body) {
        try {
            return Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new TlsKeystoreException("error.tls.pemKeyInvalid", e);
        }
    }

    private static PrivateKey decodePkcs8(byte[] der) {
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm)
                        .generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (InvalidKeySpecException | java.security.NoSuchAlgorithmException e) {
                // Try the next algorithm; the DER names its own algorithm.
            }
        }
        throw new TlsKeystoreException("error.tls.pemKeyInvalid");
    }

    private static PrivateKey decodePkcs1(byte[] der) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs1ToPkcs8(der)));
        } catch (InvalidKeySpecException | java.security.NoSuchAlgorithmException e) {
            throw new TlsKeystoreException("error.tls.pemKeyInvalid", e);
        }
    }

    /** PKCS#8 = SEQUENCE { INTEGER 0, rsaEncryption AlgorithmIdentifier, OCTET STRING(pkcs1) }. */
    private static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(version);
        out.writeBytes(algorithm);
        out.writeBytes(tlv(0x04, pkcs1));
        return tlv(0x30, out.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (value.length < 0x80) {
            out.write(value.length);
        } else {
            int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(value.length) + 7) / 8;
            out.write(0x80 | bytes);
            for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
                out.write((value.length >>> shift) & 0xff);
            }
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static void requireMatching(X509Certificate certificate, PrivateKey key) {
        String signatureAlgorithm = signatureAlgorithm(key.getAlgorithm());
        if (signatureAlgorithm == null) {
            throw new TlsKeystoreException("error.tls.pemKeyInvalid");
        }
        try {
            Signature signer = Signature.getInstance(signatureAlgorithm);
            signer.initSign(key);
            signer.update(KEY_CHECK_PROBE);
            byte[] signature = signer.sign();

            Signature verifier = Signature.getInstance(signatureAlgorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(KEY_CHECK_PROBE);
            if (!verifier.verify(signature)) {
                throw new TlsKeystoreException("error.tls.pemKeyMismatch");
            }
        } catch (InvalidKeyException e) {
            throw new TlsKeystoreException("error.tls.pemKeyMismatch", e);
        } catch (GeneralSecurityException e) {
            throw new TlsKeystoreException("error.tls.pemKeyInvalid", e);
        }
    }

    private static String signatureAlgorithm(String keyAlgorithm) {
        switch (keyAlgorithm) {
            case "RSA":
                return "SHA256withRSA";
            case "EC":
                return "SHA256withECDSA";
            case "Ed25519":
                return "Ed25519";
            case "Ed448":
                return "Ed448";
            case "DSA":
                return "SHA256withDSA";
            default:
                return null;
        }
    }
}
