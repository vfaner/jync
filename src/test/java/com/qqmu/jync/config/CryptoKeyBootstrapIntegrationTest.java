package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * End-to-end: the EPP resolves and injects before the Spring context sees
 * {@link SyncProperties}. A fresh H2 store gets generated secrets; an existing key file
 * gets loaded.
 */
class CryptoKeyBootstrapIntegrationTest {

    @Test
    void aFreshInstallGeneratesAndInjectsASecretBeforeTheContextStarts() throws Exception {
        Path dir = Files.createTempDirectory("jync-crypto-epp-");
        String url = "jdbc:h2:file:" + dir.resolve("fresh") + ";MODE=MySQL";

        CryptoKeyBootstrap bootstrap = new CryptoKeyBootstrap();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("spring.datasource.url", url,
                       "sync.crypto-password", SyncProperties.DEFAULT_CRYPTO_PASSWORD,
                       "sync.crypto-salt", SyncProperties.DEFAULT_CRYPTO_SALT)));

        bootstrap.postProcessEnvironment(env, null);

        assertThat(env.getProperty("sync.crypto-password"))
                .isNotEqualTo(SyncProperties.DEFAULT_CRYPTO_PASSWORD)
                .hasSizeGreaterThanOrEqualTo(40);
        assertThat(env.getProperty("sync.crypto-salt"))
                .isNotEqualTo(SyncProperties.DEFAULT_CRYPTO_SALT)
                .matches("[0-9a-f]{32}");
        assertThat(dir.resolve("fresh.key")).exists();
    }

    @Test
    void anExistingKeyFileIsLoadedAndInjected() throws Exception {
        Path dir = Files.createTempDirectory("jync-crypto-epp-");
        String url = "jdbc:h2:file:" + dir.resolve("keyed") + ";MODE=MySQL";

        // Pre-seed a key file
        java.util.Properties props = new java.util.Properties();
        props.setProperty("password", "my-secret-password-01234567890123456789");
        props.setProperty("salt", "fedcba98765432100123456789abcdef");
        try (var out = Files.newBufferedWriter(dir.resolve("keyed.key"))) {
            props.store(out, null);
        }

        CryptoKeyBootstrap bootstrap = new CryptoKeyBootstrap();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("spring.datasource.url", url,
                       "sync.crypto-password", SyncProperties.DEFAULT_CRYPTO_PASSWORD,
                       "sync.crypto-salt", SyncProperties.DEFAULT_CRYPTO_SALT)));

        bootstrap.postProcessEnvironment(env, null);

        assertThat(env.getProperty("sync.crypto-password"))
                .isEqualTo("my-secret-password-01234567890123456789");
        assertThat(env.getProperty("sync.crypto-salt"))
                .isEqualTo("fedcba98765432100123456789abcdef");
    }

    @Test
    void anExplicitOverrideIsNotReplaced() throws Exception {
        Path dir = Files.createTempDirectory("jync-crypto-epp-");
        String url = "jdbc:h2:file:" + dir.resolve("overridden") + ";MODE=MySQL";

        CryptoKeyBootstrap bootstrap = new CryptoKeyBootstrap();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("spring.datasource.url", url,
                       "sync.crypto-password", "operator-chosen",
                       "sync.crypto-salt", "0123456789abcdef")));

        bootstrap.postProcessEnvironment(env, null);

        assertThat(env.getProperty("sync.crypto-password")).isEqualTo("operator-chosen");
        assertThat(env.getProperty("sync.crypto-salt")).isEqualTo("0123456789abcdef");
        assertThat(dir.resolve("overridden.key")).doesNotExist();
    }

    @Test
    void anExistingStoreKeepsTheDefaultsAndWritesNoKeyFile() throws Exception {
        Path dir = Files.createTempDirectory("jync-crypto-epp-");
        Files.createFile(dir.resolve("legacy.mv.db"));
        String url = "jdbc:h2:file:" + dir.resolve("legacy") + ";MODE=MySQL";

        CryptoKeyBootstrap bootstrap = new CryptoKeyBootstrap();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("spring.datasource.url", url,
                       "sync.crypto-password", SyncProperties.DEFAULT_CRYPTO_PASSWORD,
                       "sync.crypto-salt", SyncProperties.DEFAULT_CRYPTO_SALT)));

        bootstrap.postProcessEnvironment(env, null);

        assertThat(env.getProperty("sync.crypto-password"))
                .isEqualTo(SyncProperties.DEFAULT_CRYPTO_PASSWORD);
        assertThat(env.getProperty("sync.crypto-salt"))
                .isEqualTo(SyncProperties.DEFAULT_CRYPTO_SALT);
        assertThat(dir.resolve("legacy.key")).doesNotExist();
    }
}
