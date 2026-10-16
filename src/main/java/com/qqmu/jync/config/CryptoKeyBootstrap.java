package com.qqmu.jync.config;

import java.nio.file.Path;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * First-boot crypto key bootstrap.
 *
 * <p>Runs as an {@link EnvironmentPostProcessor} before the Spring context starts, so the
 * resolved password/salt are seen by {@link com.qqmu.jync.config.SyncProperties} binding.
 * If the operator has overridden either half, this does nothing — explicit configuration
 * always wins.
 */
public class CryptoKeyBootstrap implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        Path storeBase = LegacyStoreMigration.configuredStoreBase(url);
        if (storeBase == null) {
            // Not a file-based H2 store; nothing to persist beside.
            return;
        }
        String configuredPassword = environment.getProperty("sync.crypto-password");
        String configuredSalt = environment.getProperty("sync.crypto-salt");
        CryptoKeyResolver.Secrets secrets = CryptoKeyResolver.forStoreBase(storeBase)
                .resolve(configuredPassword, configuredSalt);
        // Only inject when the generated or key-file value is in play; explicit override
        // keeps whatever the user configured.
        if (secrets.source() == CryptoKeyResolver.Source.GENERATED
                || secrets.source() == CryptoKeyResolver.Source.KEY_FILE) {
            environment.getPropertySources().addFirst(new MapPropertySource("jyncCryptoKey",
                    Map.of("sync.crypto-password", secrets.password(),
                           "sync.crypto-salt", secrets.salt())));
        }
    }
}
