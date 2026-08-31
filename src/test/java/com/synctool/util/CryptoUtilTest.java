package com.synctool.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.synctool.config.SyncProperties;

class CryptoUtilTest {

    private CryptoUtil crypto;

    @BeforeEach
    void setUp() {
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword("test-key");
        properties.setCryptoSalt("5c0744940b5c369b");
        crypto = new CryptoUtil(properties);
    }

    @Test
    void passwordRoundTrips() {
        String plain = "s3cr3t-p@ssw0rd";
        String encrypted = crypto.encrypt(plain);

        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(crypto.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    void anAlreadyEncryptedValueIsNotEncryptedAgain() {
        // Re-saving an entity without touching the password must not double-encrypt it,
        // which would make the stored value undecryptable.
        String once = crypto.encrypt("password");
        String twice = crypto.encrypt(once);

        assertThat(twice).isEqualTo(once);
        assertThat(crypto.decrypt(twice)).isEqualTo("password");
    }

    @Test
    void plaintextWrittenBeforeEncryptionWasEnabledIsStillReadable() {
        // Without the marker prefix the value predates encryption; returning it as-is keeps
        // existing connections working after an upgrade.
        assertThat(crypto.decrypt("legacy-plaintext")).isEqualTo("legacy-plaintext");
    }

    @Test
    void encryptionIsRandomizedPerValue() {
        // Identical passwords must not produce identical ciphertext, or the store would leak
        // which connections share a password.
        String a = crypto.encrypt("same");
        String b = crypto.encrypt("same");

        assertThat(a).isNotEqualTo(b);
        assertThat(crypto.decrypt(a)).isEqualTo("same");
        assertThat(crypto.decrypt(b)).isEqualTo("same");
    }

    @Test
    void nullAndEmptyValuesPassThroughUnchanged() {
        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.encrypt("")).isEmpty();
        assertThat(crypto.decrypt(null)).isNull();
        assertThat(crypto.decrypt("")).isEmpty();
    }
}
