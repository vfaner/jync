package com.qqmu.jync.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
 * fresh install are all left alone. A failure before the commit — including a value no key can
 * read — discards the pending file and keeps the previous behaviour rather than blocking
 * startup. A failure after the commit is different: the row ciphertext already belongs to the
 * pending key, so the file is kept and the instance runs on it, leaving only the promotion for
 * the next start.
 *
 * <p>Instances sharing one store start one at a time: whoever holds {@code <store>.key.lock}
 * migrates, and a second instance that finds the lock taken refuses to start rather than run on
 * the public default key for its lifetime. Once the key file exists every instance adopts it,
 * including one whose environment was resolved before the file appeared.
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
        if (!resolver.storeExists()) {
            return;
        }
        if (resolver.keyFileExists()) {
            // Usually the bootstrap already loaded it. The other case is another instance that
            // promoted it between that bootstrap and now: adopting it keeps this instance on
            // the same key as the rows instead of on the public default for its lifetime.
            adoptKeyFile(resolver);
            return;
        }

        Path lockFile = resolver.lockFile();
        // The lock file is never deleted: unlinking one that another process still holds would
        // let a third process create a fresh file, lock that, and migrate concurrently. An empty
        // leftover is harmless — the OS releases the lock when the holder exits.
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                throw migratingElsewhere(lockFile);
            }
            migrateLocked(resolver);
        } catch (OverlappingFileLockException e) {
            throw migratingElsewhere(lockFile);
        } catch (IOException e) {
            log.warn("Could not lock {} for the crypto-key migration; stored credentials keep "
                    + "using the built-in key.", lockFile, e);
        }
    }

    /**
     * Refuses to start while another instance is re-encrypting the credentials.
     *
     * <p>Continuing would leave this instance on the shipped default key while the rows belong
     * to the other instance's generated key: every credential saved here until the next restart
     * would be encrypted with a publicly known key and be unreadable everywhere else. Failing
     * to start is recoverable — the migration is short, so a restart after it finishes boots
     * normally against the promoted key file.
     */
    private IllegalStateException migratingElsewhere(Path lockFile) {
        return new IllegalStateException("Another Jync instance is migrating the stored crypto "
                + "key (" + lockFile + " is locked). Start this instance again once that has "
                + "finished; the generated key file is then picked up on boot.");
    }

    private void migrateLocked(CryptoKeyResolver resolver) {
        if (resolver.keyFileExists()) {
            // Another instance finished between our first check and taking the lock.
            adoptKeyFile(resolver);
            return;
        }
        CryptoKeyResolver.Secrets target;
        try {
            List<DatabaseConfig> configs = configRepository.findAll();
            List<AiProvider> providers = providerRepository.findAll();

            target = resolver.pendingKeyFileExists()
                    ? resolver.readPending()
                    : resolver.generatePending();
            TextEncryptor targetEncryptor = Encryptors.delux(target.password(), target.salt());

            if (!allStoredSecretsReadableBy(targetEncryptor, configs, providers)) {
                transactionTemplate.executeWithoutResult(
                        status -> reencrypt(configs, providers, targetEncryptor));
            }
        } catch (RuntimeException e) {
            // Nothing reached the database: drop the half-made key and try again next boot.
            resolver.discardPending();
            log.warn("Could not migrate the stored credentials to a generated crypto key; "
                    + "they keep using the built-in, publicly known key. Re-enter the "
                    + "connection password if it can no longer be decrypted: "
                    + e.getMessage(), e);
            return;
        }

        try {
            // The key file is the commit marker: never present before the rows are readable.
            resolver.promotePending();
        } catch (RuntimeException e) {
            // The rows are already committed under this key. Deleting the pending file here
            // would make every stored credential permanently unreadable, and running on the old
            // key would encrypt anything saved from now on under a key the database no longer
            // matches. Use the pending key in memory and leave it in place: the next start sees
            // the rows are already readable by it and only has to promote.
            adopt(target);
            log.error("Stored credentials were re-encrypted, but the generated key could not be "
                    + "written to {}. This process uses {} instead — keep that file until the "
                    + "move succeeds. Fix the data-directory permissions and restart so it "
                    + "becomes {}: {}", resolver.keyFile(), resolver.pendingKeyFile(),
                    resolver.keyFile(), e.getMessage(), e);
            return;
        }
        adopt(target);
        log.info("Re-encrypted the stored database passwords and API keys with a key "
                + "generated for this installation.");
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

    private void adoptKeyFile(CryptoKeyResolver resolver) {
        adopt(resolver.readKeyFile());
        log.info("Stored database passwords and API keys are encrypted with the key in {}; "
                + "using it for this instance too.", resolver.keyFile());
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
