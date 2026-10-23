package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.util.CryptoUtil;

class ExternalIdentityTest {

    private final CryptoUtil crypto = crypto();

    private static CryptoUtil crypto() {
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword("test-password");
        properties.setCryptoSalt("0011223344556677");
        return new CryptoUtil(properties);
    }

    @Test
    void controlCharactersAreStrippedAndTheNameIsCappedAtSixtyFour() {
        String raw = " al\u0000ice\n" + "x".repeat(80);

        String cleaned = ExternalIdentity.sanitize(raw);

        assertThat(cleaned).startsWith("alice");
        assertThat(cleaned).doesNotContain("\u0000", "\n");
        assertThat(cleaned).hasSize(64);
    }

    @Test
    void aBlankIdentityIsNoIdentity() {
        assertThat(ExternalIdentity.sanitize(" \t ")).isNull();
        assertThat(ExternalIdentity.sanitize(null)).isNull();
    }

    @Test
    void anAdminListMatchesTrimmedNamesExactly() {
        OaAuthConfig config = new OaAuthConfig();
        config.setAdminUsers(" root , ops ");

        assertThat(ExternalIdentity.role(config, "root", false)).isEqualTo(UserRole.ADMIN);
        assertThat(ExternalIdentity.role(config, "ops", false)).isEqualTo(UserRole.ADMIN);
        assertThat(ExternalIdentity.role(config, "Root", false)).isEqualTo(UserRole.VIEWER);
        assertThat(ExternalIdentity.role(config, "alice", false)).isEqualTo(UserRole.VIEWER);
    }

    @Test
    void aResponseAdminFlagGrantsAdminWithoutTheList() {
        assertThat(ExternalIdentity.role(new OaAuthConfig(), "root", true))
                .isEqualTo(UserRole.ADMIN);
    }

    @Test
    void theSharedSecretMustMatchTheDecryptedConfiguration() {
        OaAuthConfig config = new OaAuthConfig();
        config.setSecretHeaderName("X-Auth-Secret");
        config.setSecretHeaderValue(crypto.encrypt("s3cr3t"));

        assertThat(ExternalIdentity.secretMatches(config, "s3cr3t", crypto)).isTrue();
        assertThat(ExternalIdentity.secretMatches(config, "wrong", crypto)).isFalse();
        assertThat(ExternalIdentity.secretMatches(config, null, crypto)).isFalse();
    }

    @Test
    void anUndecryptableSecretFailsClosed() {
        OaAuthConfig config = new OaAuthConfig();
        config.setSecretHeaderName("X-Auth-Secret");
        config.setSecretHeaderValue(CryptoUtil.PREFIX + "not-a-ciphertext");

        assertThat(ExternalIdentity.secretMatches(config, "anything", crypto)).isFalse();
    }
}
