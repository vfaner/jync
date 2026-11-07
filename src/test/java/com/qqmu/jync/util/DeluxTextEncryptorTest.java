package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * The replacement encryptor must stay byte-compatible with the deprecated
 * {@code Encryptors.delux} it replaces: ciphertext written by either side has to remain
 * readable by the other, or an upgrade silently locks every stored credential.
 *
 * <p>The deprecated factory is used here deliberately, as the compatibility oracle.
 */
class DeluxTextEncryptorTest {

    private static final String PASSWORD = "test-password-01234567890123456789";
    private static final String SALT = "0011223344556677";

    @SuppressWarnings("deprecation")
    @Test
    void decryptsCiphertextWrittenByTheDeprecatedDeluxEncryptor() {
        TextEncryptor legacy = Encryptors.delux(PASSWORD, SALT);
        String ciphertext = legacy.encrypt("s3cret-密码");

        assertThat(DeluxTextEncryptor.create(PASSWORD, SALT).decrypt(ciphertext))
                .isEqualTo("s3cret-密码");
    }

    @SuppressWarnings("deprecation")
    @Test
    void writesCiphertextTheDeprecatedDeluxEncryptorCanRead() {
        String ciphertext = DeluxTextEncryptor.create(PASSWORD, SALT).encrypt("round-trip");

        assertThat(Encryptors.delux(PASSWORD, SALT).decrypt(ciphertext))
                .isEqualTo("round-trip");
    }

    @Test
    void roundTripsUnicodeAndEmptyText() {
        TextEncryptor encryptor = DeluxTextEncryptor.create(PASSWORD, SALT);

        assertThat(encryptor.decrypt(encryptor.encrypt(""))).isEmpty();
        assertThat(encryptor.decrypt(encryptor.encrypt("连接密码-æøå"))).isEqualTo("连接密码-æøå");
    }
}
