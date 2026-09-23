package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import com.qqmu.jync.config.SyncProperties;

/**
 * Pins the crypto defaults from three sides.
 *
 * <p>Every 1.2.x installation has its stored connection passwords AES-encrypted with exactly
 * the pre-rename default key and salt, so those two literals are load-bearing: if a future
 * cleanup (say, another rename sweep) touches either the Java default or the packaged yml,
 * every existing deployment's credentials become undecryptable after the upgrade. These tests
 * turn that silent catastrophe into a red build.
 */
class CryptoDefaultsTest {

    @Test
    void javaDefaultsMatchThePackagedYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = Objects.requireNonNull(yaml.getObject());

        SyncProperties defaults = new SyncProperties();
        assertThat(props.getProperty("sync.crypto-password")).isEqualTo(defaults.getCryptoPassword());
        assertThat(props.getProperty("sync.crypto-salt")).isEqualTo(defaults.getCryptoSalt());
    }

    @Test
    void defaultsAreThePreRenameLiteralsAndStillDecryptLegacyCiphertext() {
        SyncProperties defaults = new SyncProperties();
        assertThat(defaults.getCryptoPassword()).isEqualTo("synctool-default-key-change-me");
        assertThat(defaults.getCryptoSalt()).isEqualTo("5c0744940b5c369b");

        // A value exactly as a 1.2.x install would have stored it.
        TextEncryptor legacy = Encryptors.delux("synctool-default-key-change-me", "5c0744940b5c369b");
        String storedOnSite = "enc:" + legacy.encrypt("golden-secret");

        assertThat(new CryptoUtil(defaults).decrypt(storedOnSite)).isEqualTo("golden-secret");
    }
}
