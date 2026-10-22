package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import com.qqmu.jync.config.SyncProperties;

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
    void plaintextThatStartsWithTheMarkerIsStillEncrypted() {
        // The "enc:" marker is ambiguous: a user password may literally start with it.
        // Trusting the prefix blindly used to store such passwords as plaintext, and
        // decrypt() would then strip the prefix and try real AES on the rest — the
        // credential never worked and re-entering the same password kept reproducing it.
        String plain = "enc:hunter2";
        String encrypted = crypto.encrypt(plain);

        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(crypto.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    void ciphertextFromAnotherKeyIsReEncryptedRatherThanPassedThrough() {
        // A prefixed value this encryptor cannot decrypt is treated as opaque plaintext
        // (e.g. the key rotated since it was saved) instead of being passed through, so
        // the stored-value invariant holds: marker present => decryptable by this key.
        SyncProperties other = new SyncProperties();
        other.setCryptoPassword("another-key");
        other.setCryptoSalt("5c0744940b5c369b");
        String foreign = new CryptoUtil(other).encrypt("password");

        String stored = crypto.encrypt(foreign);

        assertThat(stored).isNotEqualTo(foreign);
        assertThat(crypto.decrypt(stored)).isEqualTo(foreign);
    }

    @Test
    void nullAndEmptyValuesPassThroughUnchanged() {
        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.encrypt("")).isEmpty();
        assertThat(crypto.decrypt(null)).isNull();
        assertThat(crypto.decrypt("")).isEmpty();
    }

    @Test
    void reencryptMovesAValueToTheTargetKeyWithoutChangingThePlaintext() {
        // Used by the first-boot key migration: every stored secret must come out
        // decryptable by the new key and by no other.
        TextEncryptor target = Encryptors.delux("target-key", "aaaabbbbccccddddeeeeffff00001111");
        String stored = crypto.encrypt("db-password");

        String moved = crypto.reencrypt(stored, target);

        assertThat(moved).startsWith(CryptoUtil.PREFIX);
        assertThat(target.decrypt(moved.substring(CryptoUtil.PREFIX.length())))
                .isEqualTo("db-password");
        assertThat(moved).isNotEqualTo(stored);
    }

    @Test
    void reencryptEncryptsPlaintextLeftByAnEarlierVersion() {
        TextEncryptor target = Encryptors.delux("target-key", "aaaabbbbccccddddeeeeffff00001111");

        String moved = crypto.reencrypt("legacy-plaintext", target);

        assertThat(moved).startsWith(CryptoUtil.PREFIX);
        assertThat(target.decrypt(moved.substring(CryptoUtil.PREFIX.length())))
                .isEqualTo("legacy-plaintext");
    }

    @Test
    void reencryptRefusesCiphertextThatThisKeyCannotRead() {
        // Silently "migrating" an unreadable value would overwrite the only copy of the
        // ciphertext with garbage; the migration must abort instead.
        TextEncryptor target = Encryptors.delux("target-key", "aaaabbbbccccddddeeeeffff00001111");

        assertThatThrownBy(() -> crypto.reencrypt("enc:not-readable", target))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reencryptPassesNullAndEmptyValuesThrough() {
        TextEncryptor target = Encryptors.delux("target-key", "aaaabbbbccccddddeeeeffff00001111");

        assertThat(crypto.reencrypt(null, target)).isNull();
        assertThat(crypto.reencrypt("", target)).isEmpty();
    }

    @Test
    void rotateToSwitchesEveryLaterOperationToTheNewKey() {
        SyncProperties initial = new SyncProperties();
        CryptoUtil rotated = new CryptoUtil(initial);
        assertThat(rotated.isUsingDefaultKey()).isTrue();
        String oldCiphertext = rotated.encrypt("pw");

        rotated.rotateTo("fresh-key", "00112233445566778899aabbccddeeff");

        assertThat(rotated.isUsingDefaultKey()).isFalse();
        assertThat(rotated.decrypt(rotated.encrypt("later"))).isEqualTo("later");
        // The old ciphertext is no longer readable, which is why the migration re-encrypts
        // the rows before calling this.
        assertThatThrownBy(() -> rotated.decrypt(oldCiphertext))
                .isInstanceOf(IllegalStateException.class);
    }
}
