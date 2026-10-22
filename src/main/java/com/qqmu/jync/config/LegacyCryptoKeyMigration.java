package com.qqmu.jync.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.repository.AiProviderRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.util.CryptoUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Moves an existing installation off the publicly shipped crypto key.
 *
 * <p>Before this, a database that predated the generated-key support kept the defaults from
 * {@link SyncProperties}, which are printed in the open-source jar. The fix cannot simply
 * generate a key at boot — that would make every stored credential unreadable. Instead the
 * first boot after upgrading:
 *
 * <ol>
 *   <li>generates a strong key into {@code <store>.key.pending};</li>
 *   <li>re-encrypts every stored database password, miner password and AI key in one
 *       transaction;</li>
 *   <li>only after that commit, promotes the pending file to {@code <store>.key}.</li>
 * </ol>
 *
 * <p>A crash before the commit leaves the pending file and the old ciphertext: the next boot
 * detects that the pending key cannot read the rows and redoes the migration with it. A crash
 * after the commit leaves the pending file and new ciphertext: the next boot sees that it
 * reads the rows and only promotes. An explicit operator key, an existing key file, and a
 * fresh install are all left alone. Any failure — including a value no key can read — discards
 * the pending file and keeps the previous behaviour rather than blocking startup.
 */
@Component
@Slf4j
public class LegacyCryptoKeyMigration {

    private final SyncProperties properties;
    private final CryptoUtil cryptoUtil;
    private final DatabaseConfigRepository configRepository;
    private final AiProviderRepository providerRepository;
    private final TransactionTemplate transactionTemplate;
    private final String datasourceUrl;

    public LegacyCryptoKeyMigration(SyncProperties properties,
                                    CryptoUtil cryptoUtil,
                                    DatabaseConfigRepository configRepository,
                                    AiProviderRepository providerRepository,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.properties = properties;
        this.cryptoUtil = cryptoUtil;
        this.configRepository = configRepository;
        this.providerRepository = providerRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.datasourceUrl = datasourceUrl;
    }

    @PostConstruct
    public void migrate() {
        if (!usingShippedDefaults()) {
            return;
        }
        Path storeBase = LegacyStoreMigration.configuredStoreBase(datasourceUrl);
        if (storeBase == null) {
            return;
        }
        CryptoKeyResolver resolver = CryptoKeyResolver.forStoreBase(storeBase);
        if (!resolver.storeExists() || resolver.keyFileExists()) {
            return;
        }

        Path lockFile = resolver.lockFile();
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                log.info("Another Jync instance is migrating the stored crypto key; skipping.");
                return;
            }
            migrateLocked(resolver);
        } catch (OverlappingFileLockException e) {
            log.info("Another Jync instance is migrating the stored crypto key; skipping.");
        } catch (IOException e) {
            log.warn("Could not lock {} for the crypto-key migration; stored credentials keep "
                    + "using the built-in key.", lockFile, e);
        } finally {
            try {
                Files.deleteIfExists(lockFile);
            } catch (IOException ignored) {
                // A stale empty lock file is harmless: the next boot tries the same lock.
            }
        }
    }

    private void migrateLocked(CryptoKeyResolver resolver) {
        if (resolver.keyFileExists()) {
            // Another instance finished between our first check and taking the lock.
            return;
        }
        try {
            List<DatabaseConfig> configs = configRepository.findAll();
            List<AiProvider> providers = providerRepository.findAll();

            CryptoKeyResolver.Secrets target = resolver.pendingKeyFileExists()
                    ? resolver.readPending()
                    : resolver.generatePending();
            TextEncryptor targetEncryptor = Encryptors.delux(target.password(), target.salt());

            if (!allStoredSecretsReadableBy(targetEncryptor, configs, providers)) {
                transactionTemplate.executeWithoutResult(
                        status -> reencrypt(configs, providers, targetEncryptor));
            }
            // The key file is the commit marker: never present before the rows are readable.
            resolver.promotePending();
            adopt(target);
            log.info("Re-encrypted the stored database passwords and API keys with a key "
                    + "generated for this installation.");
        } catch (RuntimeException e) {
            resolver.discardPending();
            log.warn("Could not migrate the stored credentials to a generated crypto key; "
                    + "they keep using the built-in, publicly known key. Re-enter the "
                    + "connection password if it can no longer be decrypted: "
                    + e.getMessage(), e);
        }
    }

    private void reencrypt(List<DatabaseConfig> configs, List<AiProvider> providers,
                           TextEncryptor target) {
        for (DatabaseConfig config : configs) {
            config.setPassword(cryptoUtil.reencrypt(config.getPassword(), target));
            config.setMinerPassword(cryptoUtil.reencrypt(config.getMinerPassword(), target));
        }
        for (AiProvider provider : providers) {
            provider.setApiKey(cryptoUtil.reencrypt(provider.getApiKey(), target));
        }
        configRepository.saveAll(configs);
        providerRepository.saveAll(providers);
    }

    /**
     * Whether the pending key already encrypted everything — the crash-after-commit case.
     * No stored secret counts as migrated: promoting is then a no-op for the database.
     */
    private static boolean allStoredSecretsReadableBy(TextEncryptor encryptor,
                                                      List<DatabaseConfig> configs,
                                                      List<AiProvider> providers) {
        for (DatabaseConfig config : configs) {
            if (!readableBy(config.getPassword(), encryptor)
                    || !readableBy(config.getMinerPassword(), encryptor)) {
                return false;
            }
        }
        for (AiProvider provider : providers) {
            if (!readableBy(provider.getApiKey(), encryptor)) {
                return false;
            }
        }
        return true;
    }

    private static boolean readableBy(String stored, TextEncryptor encryptor) {
        if (stored == null || stored.isEmpty()) {
            // Nothing stored: nothing that could be left behind by an interrupted migration.
            return true;
        }
        if (!stored.startsWith(CryptoUtil.PREFIX)) {
            // Plaintext from a version before encryption: the re-encrypt pass is still due.
            return false;
        }
        try {
            encryptor.decrypt(stored.substring(CryptoUtil.PREFIX.length()));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void adopt(CryptoKeyResolver.Secrets target) {
        // Order matters: rotate the util first so later saves cannot still use the old key.
        cryptoUtil.rotateTo(target.password(), target.salt());
        properties.setCryptoPassword(target.password());
        properties.setCryptoSalt(target.salt());
    }

    private boolean usingShippedDefaults() {
        return SyncProperties.DEFAULT_CRYPTO_PASSWORD.equals(properties.getCryptoPassword())
                && SyncProperties.DEFAULT_CRYPTO_SALT.equals(properties.getCryptoSalt());
    }
}
