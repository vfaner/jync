package com.qqmu.jync.util;

import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.crypto.codec.Utf8;
import org.springframework.security.crypto.encrypt.AesBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;

/**
 * The hex-encoded AES-GCM text encryptor formerly provided by the deprecated
 * {@code Encryptors.delux(password, salt)}.
 *
 * <p>{@code delux} was only a thin wrapper around {@code Encryptors.stronger} plus hex
 * encoding, and {@code stronger} was in turn {@code new AesBytesEncryptor(password, salt,
 * KeyGenerators.secureRandom(16), CipherAlgorithm.GCM)}. Re-creating that exact chain here
 * is byte-compatible in both directions: ciphertext written before the upgrade keeps
 * decrypting, and values written now stay readable by older releases. Using
 * {@code Encryptors.text} instead would silently change the cipher and make every stored
 * credential unreadable.
 */
public final class DeluxTextEncryptor implements TextEncryptor {

    private final BytesEncryptor delegate;

    private DeluxTextEncryptor(BytesEncryptor delegate) {
        this.delegate = delegate;
    }

    /** Returns an encryptor equivalent to {@code Encryptors.delux(password, salt)}. */
    public static TextEncryptor create(CharSequence password, CharSequence salt) {
        BytesEncryptor aes = new AesBytesEncryptor(password.toString(), salt,
                KeyGenerators.secureRandom(16), AesBytesEncryptor.CipherAlgorithm.GCM);
        return new DeluxTextEncryptor(aes);
    }

    @Override
    public String encrypt(String text) {
        return new String(Hex.encode(delegate.encrypt(Utf8.encode(text))));
    }

    @Override
    public String decrypt(String encryptedText) {
        return Utf8.decode(delegate.decrypt(Hex.decode(encryptedText)));
    }
}
