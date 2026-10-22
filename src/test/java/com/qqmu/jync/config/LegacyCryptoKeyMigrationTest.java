package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.repository.AiProviderRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Upgrading an installation that still uses the publicly shipped key.
 *
 * <p>The migration must re-encrypt every stored secret under a generated key, leave the key file
 * only after the database has committed it, and recover from a crash at either side of that
 * boundary. Anything it cannot decrypt aborts the whole attempt rather than half-migrating rows.
 */
class LegacyCryptoKeyMigrationTest {

    @TempDir
    Path dir;

    private SyncProperties properties;
    private CryptoUtil crypto;
    private DatabaseConfigRepository configRepository;
    private AiProviderRepository providerRepository;
    private LegacyCryptoKeyMigration migration;

    @BeforeEach
    void setUp() throws IOException {
        properties = new SyncProperties();
        crypto = new CryptoUtil(properties);
        configRepository = mock(DatabaseConfigRepository.class);
        providerRepository = mock(AiProviderRepository.class);
        when(configRepository.findAll()).thenReturn(List.of());
        when(providerRepository.findAll()).thenReturn(List.of());
        // The migrator only checks that the store file exists; the repositories are the DB in
        // this test, so an empty marker is enough.
        Files.createFile(dir.resolve("jync.mv.db"));
        migration = migration("jdbc:h2:file:" + dir.resolve("jync") + ";MODE=MySQL");
    }

    private LegacyCryptoKeyMigration migration(String url) {
        return new LegacyCryptoKeyMigration(properties, crypto, configRepository,
                providerRepository, transactionManager(), url);
    }

    @Test
    void reEncryptsEveryStoredSecretUnderAGeneratedKey() throws IOException {
        DatabaseConfig config = new DatabaseConfig();
        config.setName("prod");
        config.setPassword(crypto.encrypt("db-password"));
        config.setMinerPassword(crypto.encrypt("miner-password"));
        AiProvider provider = new AiProvider();
        provider.setName("ai");
        provider.setApiKey(crypto.encrypt("api-key"));
        when(configRepository.findAll()).thenReturn(List.of(config));
        when(providerRepository.findAll()).thenReturn(List.of(provider));

        migration.migrate();

        Properties key = readKey();
        String newPassword = key.getProperty("password");
        assertThat(newPassword).isNotEqualTo(SyncProperties.DEFAULT_CRYPTO_PASSWORD);
        assertThat(key.getProperty("salt")).isNotEqualTo(SyncProperties.DEFAULT_CRYPTO_SALT);

        // The entities in the repository were re-encrypted under the new key...
        TextEncryptor fresh = Encryptors.delux(newPassword, key.getProperty("salt"));
        assertThat(crypto.isUsingDefaultKey()).isFalse();
        assertThat(decryptWith(config.getPassword(), fresh)).isEqualTo("db-password");
        assertThat(decryptWith(config.getMinerPassword(), fresh)).isEqualTo("miner-password");
        assertThat(decryptWith(provider.getApiKey(), fresh)).isEqualTo("api-key");
        // ...and no longer readable with the public default.
        assertThat(canDecrypt(config.getPassword(),
                Encryptors.delux(SyncProperties.DEFAULT_CRYPTO_PASSWORD,
                        SyncProperties.DEFAULT_CRYPTO_SALT))).isFalse();

        verify(configRepository).saveAll(anyList());
        verify(providerRepository).saveAll(anyList());
        // The running configuration now points at the new key, so the banner disappears and
        // every later save uses it too.
        assertThat(properties.getCryptoPassword()).isEqualTo(newPassword);
        assertThat(properties.getCryptoSalt()).isEqualTo(key.getProperty("salt"));
        assertThat(crypto.decrypt(crypto.encrypt("later-secret"))).isEqualTo("later-secret");
    }

    @Test
    void anExplicitOperatorKeyIsLeftAlone() {
        properties.setCryptoPassword("operator-chosen");
        properties.setCryptoSalt("0123456789abcdef");

        migration.migrate();

        assertThat(dir.resolve("jync.key")).doesNotExist();
        verify(configRepository, never()).findAll();
        verify(providerRepository, never()).findAll();
    }

    @Test
    void anExistingKeyFileMeansTheWorkIsAlreadyDone() throws IOException {
        // What the EnvironmentPostProcessor leaves behind on a fresh install or after a
        // previous migration.
        Files.writeString(dir.resolve("jync.key"),
                "password=already-generated\nsalt=00112233445566778899aabbccddeeff\n");

        migration.migrate();

        verify(configRepository, never()).findAll();
        assertThat(properties.getCryptoPassword())
                .isEqualTo(SyncProperties.DEFAULT_CRYPTO_PASSWORD);
    }

    @Test
    void aPendingKeyThatAlreadyEncryptedTheRowsIsPromoted() throws IOException {
        TextEncryptor pending = Encryptors.delux(
                "pending-password-0123456789012345678901", "aaaabbbbccccddddeeeeffff00001111");
        Files.writeString(dir.resolve("jync.key.pending"),
                "password=pending-password-0123456789012345678901\n"
                        + "salt=aaaabbbbccccddddeeeeffff00001111\n");
        DatabaseConfig config = new DatabaseConfig();
        config.setPassword("enc:" + pending.encrypt("db-password"));
        when(configRepository.findAll()).thenReturn(List.of(config));

        migration.migrate();

        assertThat(readKey().getProperty("password"))
                .isEqualTo("pending-password-0123456789012345678901");
        assertThat(dir.resolve("jync.key.pending")).doesNotExist();
        // Already migrated, so the rows must not be touched again.
        verify(configRepository, never()).saveAll(anyList());
        assertThat(decryptWith(config.getPassword(), pending)).isEqualTo("db-password");
        assertThat(properties.getCryptoPassword())
                .isEqualTo("pending-password-0123456789012345678901");
    }

    @Test
    void aPendingKeyThatDidNotReachTheDatabaseIsUsedToRedoTheMigration() throws IOException {
        // Crash before commit: the pending file exists, the rows still carry the old key.
        Files.writeString(dir.resolve("jync.key.pending"),
                "password=pending-password-0123456789012345678901\n"
                        + "salt=aaaabbbbccccddddeeeeffff00001111\n");
        DatabaseConfig config = new DatabaseConfig();
        config.setPassword(crypto.encrypt("db-password"));
        when(configRepository.findAll()).thenReturn(List.of(config));

        migration.migrate();

        TextEncryptor pending = Encryptors.delux(
                "pending-password-0123456789012345678901", "aaaabbbbccccddddeeeeffff00001111");
        assertThat(readKey().getProperty("password"))
                .isEqualTo("pending-password-0123456789012345678901");
        assertThat(decryptWith(config.getPassword(), pending)).isEqualTo("db-password");
        verify(configRepository).saveAll(anyList());
    }

    @Test
    void anUndecryptableValueAbortsWithoutWritingAKeyFile() {
        DatabaseConfig config = new DatabaseConfig();
        config.setPassword("enc:not-readable-by-any-key");
        when(configRepository.findAll()).thenReturn(List.of(config));

        migration.migrate();

        assertThat(dir.resolve("jync.key")).doesNotExist();
        assertThat(dir.resolve("jync.key.pending")).doesNotExist();
        verify(configRepository, never()).saveAll(anyList());
        assertThat(properties.getCryptoPassword())
                .isEqualTo(SyncProperties.DEFAULT_CRYPTO_PASSWORD);
        assertThat(crypto.isUsingDefaultKey()).isTrue();
    }

    @Test
    void aStoreThatIsNotAFileIsSkipped() {
        LegacyCryptoKeyMigration inMemory = migration(
                "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1");

        inMemory.migrate();

        verify(configRepository, never()).findAll();
        assertThat(dir.resolve("jync.key")).doesNotExist();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Properties readKey() {
        Path key = dir.resolve("jync.key");
        assertThat(key).exists();
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(key)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return props;
    }

    private static String decryptWith(String stored, TextEncryptor encryptor) {
        return encryptor.decrypt(stored.substring(CryptoUtil.PREFIX.length()));
    }

    private static boolean canDecrypt(String stored, TextEncryptor encryptor) {
        try {
            decryptWith(stored, encryptor);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Runs the callback without an actual database; the repositories are the assertions. */
    private static PlatformTransactionManager transactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }
}
