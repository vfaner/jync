package com.qqmu.jync.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The envelope around an exported configuration package.
 *
 * <p>Nothing in the package is readable without the passphrase — not even the entry names — so the
 * whole zip sits inside the AEAD ciphertext, and the header that has to stay in the clear is
 * authenticated as additional data instead. A wrong passphrase and a flipped ciphertext byte are
 * deliberately the same error: distinguishing them would tell an attacker which half they got
 * right.
 */
class ConfigPackageCryptoTest {

    private static final char[] PASSPHRASE = "correct horse battery".toCharArray();
    private static final byte[] ZIP = "PK\u0003\u0004 not really a zip".getBytes(StandardCharsets.UTF_8);

    private final ConfigPackageCrypto crypto = new ConfigPackageCrypto();

    @Test
    @DisplayName("a package round-trips with its state flag intact")
    void roundTrips() {
        byte[] sealed = crypto.encrypt(ZIP, PASSPHRASE, true);

        ConfigPackageCrypto.Opened opened = crypto.decrypt(sealed, PASSPHRASE);

        assertThat(opened.zip()).isEqualTo(ZIP);
        assertThat(opened.includeState()).isTrue();
        assertThat(crypto.decrypt(crypto.encrypt(ZIP, PASSPHRASE, false), PASSPHRASE).includeState())
                .isFalse();
    }

    @Test
    @DisplayName("the header is magic, version, flags, salt, IV, then ciphertext")
    void headerLayout() {
        byte[] sealed = crypto.encrypt(ZIP, PASSPHRASE, true);

        assertThat(Arrays.copyOfRange(sealed, 0, 4))
                .containsExactly('J', 'Y', 'N', 'C');
        assertThat(sealed[4]).isEqualTo((byte) ConfigPackageFormat.FORMAT_VERSION);
        assertThat(sealed[5]).isEqualTo((byte) 1);
        assertThat(sealed).hasSize(ConfigPackageFormat.HEADER_BYTES + ZIP.length
                + ConfigPackageFormat.TAG_BITS / 8);

        // A second export of the same bytes must not look the same: salt and IV are random.
        assertThat(crypto.encrypt(ZIP, PASSPHRASE, true)).isNotEqualTo(sealed);
    }

    @Test
    @DisplayName("nothing recognizable survives in the sealed bytes")
    void thePlaintextIsNotInTheClear() {
        byte[] plain = "{\"databaseName\":\"prod-orders\"}".getBytes(StandardCharsets.UTF_8);

        String sealedAsText = new String(crypto.encrypt(plain, PASSPHRASE, false),
                StandardCharsets.ISO_8859_1);

        assertThat(sealedAsText).doesNotContain("databaseName");
    }

    @Test
    @DisplayName("a wrong passphrase and a tampered byte report the same error")
    void wrongPassphraseAndTamperingAreIndistinguishable() {
        byte[] sealed = crypto.encrypt(ZIP, PASSPHRASE, true);

        byte[] flippedFlag = sealed.clone();
        flippedFlag[5] ^= 0x01;
        byte[] flippedBody = sealed.clone();
        flippedBody[40] ^= 0x01;

        assertThatThrownBy(() -> crypto.decrypt(sealed, "wrong passphrase".toCharArray()))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.badPassphrase");
        assertThatThrownBy(() -> crypto.decrypt(flippedFlag, PASSPHRASE))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.badPassphrase");
        assertThatThrownBy(() -> crypto.decrypt(flippedBody, PASSPHRASE))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.badPassphrase");
    }

    @Test
    @DisplayName("the passphrase has a length floor and a length ceiling")
    void passphraseLengthBounds() {
        assertThatThrownBy(() -> crypto.encrypt(ZIP, "1234567".toCharArray(), false))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.passphrase.tooShort");
        assertThatThrownBy(() -> crypto.encrypt(ZIP, null, false))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.passphrase.tooShort");

        char[] longest = new char[ConfigPackageFormat.MAX_PASSPHRASE_CHARS + 1];
        Arrays.fill(longest, 'a');
        assertThatThrownBy(() -> crypto.encrypt(ZIP, longest, false))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.passphrase.tooLong");

        // Exactly the floor works.
        crypto.encrypt(ZIP, "12345678".toCharArray(), false);
    }

    @Test
    @DisplayName("a package from a newer format version is refused by name")
    void aNewerFormatIsRefused() {
        byte[] sealed = crypto.encrypt(ZIP, PASSPHRASE, false);
        sealed[4] = (byte) (ConfigPackageFormat.FORMAT_VERSION + 1);

        assertThatThrownBy(() -> crypto.decrypt(sealed, PASSPHRASE))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.schemaTooNew");
    }

    @Test
    @DisplayName("a file that is not a package at all is refused as a format error")
    void aForeignFileIsNotAPackage() {
        assertThatThrownBy(() -> crypto.decrypt("PK\u0003\u0004 a plain zip".getBytes(StandardCharsets.UTF_8),
                PASSPHRASE))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.badFormat");
        assertThatThrownBy(() -> crypto.decrypt(new byte[0], PASSPHRASE))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.badFormat");
        assertThatThrownBy(() -> crypto.decrypt(Arrays.copyOf("JYNC".getBytes(StandardCharsets.UTF_8), 10),
                PASSPHRASE))
                .isInstanceOf(ConfigPackageException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.config.import.badFormat");
    }
}
