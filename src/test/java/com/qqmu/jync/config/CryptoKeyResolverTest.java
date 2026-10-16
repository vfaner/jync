package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import com.qqmu.jync.config.CryptoKeyResolver.Secrets;
import com.qqmu.jync.config.CryptoKeyResolver.Source;

/**
 * First-boot crypto key resolution.
 *
 * <p>The shipped default password/salt are public (they are in the open-source jar), so a
 * fresh install must instead get a generated secret persisted beside its database. The two
 * compatibility boundaries that make this safe are: an operator override always wins, and an
 * existing database (the renamed-from-SyncTool file included) keeps the shipped defaults —
 * its stored ciphertext was encrypted with them, so "upgrading" the key would orphan every
 * saved password.
 */
class CryptoKeyResolverTest {

    @TempDir
    Path dir;

    private CryptoKeyResolver resolver() {
        return CryptoKeyResolver.forStoreBase(dir.resolve("jync"));
    }

    private Path keyFile() {
        return dir.resolve("jync.key");
    }

    private void createStore(String fileName) throws Exception {
        Files.createFile(dir.resolve(fileName));
    }

    @Test
    void anExplicitOperatorOverrideWinsAndNeverCreatesAKeyFile() {
        Secrets secrets = resolver().resolve("operator-chosen", "0123456789abcdef");

        assertThat(secrets.source()).isEqualTo(Source.EXPLICIT);
        assertThat(secrets.password()).isEqualTo("operator-chosen");
        assertThat(secrets.salt()).isEqualTo("0123456789abcdef");
        assertThat(keyFile()).doesNotExist();
    }

    @Test
    void changingOnlyOneHalfStillCountsAsAnExplicitOverride() {
        Secrets secrets = resolver()
                .resolve("operator-chosen", SyncProperties.DEFAULT_CRYPTO_SALT);

        assertThat(secrets.source()).isEqualTo(Source.EXPLICIT);
        assertThat(keyFile()).doesNotExist();
    }

    @Test
    void aFreshInstallGeneratesPersistsAndUsesAStrongSecret() throws Exception {
        Secrets secrets = resolver()
                .resolve(SyncProperties.DEFAULT_CRYPTO_PASSWORD, SyncProperties.DEFAULT_CRYPTO_SALT);

        assertThat(secrets.source()).isEqualTo(Source.GENERATED);
        assertThat(secrets.password())
                .isNotEqualTo(SyncProperties.DEFAULT_CRYPTO_PASSWORD)
                .hasSizeGreaterThanOrEqualTo(40);
        assertThat(secrets.salt())
                .isNotEqualTo(SyncProperties.DEFAULT_CRYPTO_SALT)
                .matches("[0-9a-f]{32}");
        assertThat(keyFile()).exists();

        // The persisted pair must actually drive a working AES encryptor.
        TextEncryptor encryptor = Encryptors.delux(secrets.password(), secrets.salt());
        String stored = "enc:" + encryptor.encrypt("golden-secret");
        assertThat(encryptor.decrypt(stored.substring(4))).isEqualTo("golden-secret");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void thePersistedKeyFileIsOwnerOnly() throws Exception {
        resolver().resolve(SyncProperties.DEFAULT_CRYPTO_PASSWORD, SyncProperties.DEFAULT_CRYPTO_SALT);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyFile());
        assertThat(perms).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void aSecondBootLoadsTheSameSecretsFromTheKeyFile() {
        CryptoKeyResolver resolver = resolver();
        Secrets first = resolver.resolve(
                SyncProperties.DEFAULT_CRYPTO_PASSWORD, SyncProperties.DEFAULT_CRYPTO_SALT);
        Secrets second = resolver.resolve(
                SyncProperties.DEFAULT_CRYPTO_PASSWORD, SyncProperties.DEFAULT_CRYPTO_SALT);

        assertThat(second.source()).isEqualTo(Source.KEY_FILE);
        assertThat(second.password()).isEqualTo(first.password());
        assertThat(second.salt()).isEqualTo(first.salt());
    }

    @Test
    void anExistingTargetStoreKeepsTheShippedDefaultsAndWritesNoKeyFile() throws Exception {
        createStore("jync.mv.db");

        Secrets secrets = resolver().resolve(
                SyncProperties.DEFAULT_CRYPTO_PASSWORD, SyncProperties.DEFAULT_CRYPTO_SALT);

        assertThat(secrets.source()).isEqualTo(Source.LEGACY_DEFAULT);
        assertThat(secrets.password()).isEqualTo(SyncProperties.DEFAULT_CRYPTO_PASSWORD);
        assertThat(secrets.salt()).isEqualTo(SyncProperties.DEFAULT_CRYPTO_SALT);
        assertThat(keyFile()).doesNotExist();
    }

    @Test
    void anUnMigratedSyncToolStoreAlsoKeepsTheShippedDefaults() throws Exception {
        // LegacyStoreMigration renames this either just before or just after this resolver runs;
        // whichever the order, its mere presence marks an upgraded install that must not get a
        // freshly generated key.
        createStore("synctool.mv.db");

        Secrets secrets = resolver().resolve(
                SyncProperties.DEFAULT_CRYPTO_PASSWORD, SyncProperties.DEFAULT_CRYPTO_SALT);

        assertThat(secrets.source()).isEqualTo(Source.LEGACY_DEFAULT);
        assertThat(keyFile()).doesNotExist();
    }

    @Test
    void anExistingKeyFileIsHonouredEvenWhenNoStoreIsPresentYet() throws Exception {
        // Store deleted/recreated but key retained: the key file is the source of truth.
        Properties props = new Properties();
        props.setProperty("password", "persisted-secret");
        props.setProperty("salt", "0123456789abcdef");
        try (var out = Files.newBufferedWriter(keyFile())) {
            props.store(out, null);
        }

        Secrets secrets = resolver().resolve(
                SyncProperties.DEFAULT_CRYPTO_PASSWORD, SyncProperties.DEFAULT_CRYPTO_SALT);

        assertThat(secrets.source()).isEqualTo(Source.KEY_FILE);
        assertThat(secrets.password()).isEqualTo("persisted-secret");
        assertThat(secrets.salt()).isEqualTo("0123456789abcdef");
    }

    @Test
    void twoFreshInstallsGetDifferentSecrets() {
        Secrets a = resolver().resolve(
                SyncProperties.DEFAULT_CRYPTO_PASSWORD, SyncProperties.DEFAULT_CRYPTO_SALT);
        Secrets b = CryptoKeyResolver.forStoreBase(dir.resolve("other")).resolve(
                SyncProperties.DEFAULT_CRYPTO_PASSWORD, SyncProperties.DEFAULT_CRYPTO_SALT);

        assertThat(b.password()).isNotEqualTo(a.password());
        assertThat(b.salt()).isNotEqualTo(a.salt());
    }

    @Test
    void aCorruptKeyFileFailsClosedInsteadOfSilentlyRegenerating() throws Exception {
        Files.writeString(keyFile(), "password=only-half-a-record\n");

        assertThatThrownBy(() -> resolver().resolve(
                SyncProperties.DEFAULT_CRYPTO_PASSWORD, SyncProperties.DEFAULT_CRYPTO_SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jync.key");
    }
}
