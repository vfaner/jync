package com.qqmu.jync.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Moving a secret from the exporting deployment's key to this one's.
 *
 * <p>The whole point of carrying {@code meta.sourceCrypto} in the package: a non-H2 metadata
 * database never loads {@code data/jync.key}, so the target runs on the shipped default key. The
 * export's {@code enc:} values are unreadable there unless they are decrypted with the source key
 * and written again with the target's.
 */
class ConfigSecretTranslatorTest {

    private static final String SOURCE_PASSWORD = "source-key-material";
    private static final String SOURCE_SALT = "0123456789abcdef";

    @Test
    @DisplayName("ciphertext written with the source key is rewritten for the target key")
    void ciphertextIsTranslated() {
        CryptoUtil source = new CryptoUtil(properties(SOURCE_PASSWORD, SOURCE_SALT));
        CryptoUtil target = new CryptoUtil(properties("target-key-material", "fedcba9876543210"));
        ConfigSecretTranslator translator = new ConfigSecretTranslator(target);

        String storedAtSource = source.encrypt("s3cret");

        String storedAtTarget = translator.toTargetKey(storedAtSource, SOURCE_PASSWORD,
                SOURCE_SALT);

        assertThat(storedAtTarget).isNotEqualTo(storedAtSource);
        assertThat(storedAtTarget).startsWith(CryptoUtil.PREFIX);
        assertThat(target.decrypt(storedAtTarget)).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("a legacy plaintext value is encrypted on the way in")
    void plaintextIsEncrypted() {
        CryptoUtil target = new CryptoUtil(properties("target-key-material", "fedcba9876543210"));
        ConfigSecretTranslator translator = new ConfigSecretTranslator(target);

        String stored = translator.toTargetKey("not-yet-encrypted", SOURCE_PASSWORD, SOURCE_SALT);

        assertThat(stored).startsWith(CryptoUtil.PREFIX);
        assertThat(target.decrypt(stored)).isEqualTo("not-yet-encrypted");
    }

    @Test
    @DisplayName("a value the source key cannot read aborts, and the message names no value")
    void unreadableCiphertextAborts() {
        CryptoUtil target = new CryptoUtil(properties("target-key-material", "fedcba9876543210"));
        ConfigSecretTranslator translator = new ConfigSecretTranslator(target);

        assertThatThrownBy(() -> translator.toTargetKey(
                CryptoUtil.PREFIX + "bm90LXJlYWwtY2lwaGVydGV4dA==", SOURCE_PASSWORD, SOURCE_SALT))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.secretUnreadable")
                .hasMessageNotContaining("bm90LXJlYWwtY2lwaGVydGV4dA==");
    }

    @Test
    @DisplayName("empty and null values pass through untouched")
    void blanksPassThrough() {
        CryptoUtil target = new CryptoUtil(properties("target-key-material", "fedcba9876543210"));
        ConfigSecretTranslator translator = new ConfigSecretTranslator(target);

        assertThat(translator.toTargetKey(null, SOURCE_PASSWORD, SOURCE_SALT)).isNull();
        assertThat(translator.toTargetKey("", SOURCE_PASSWORD, SOURCE_SALT)).isEmpty();
    }

    private static SyncProperties properties(String password, String salt) {
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword(password);
        properties.setCryptoSalt(salt);
        return properties;
    }
}
