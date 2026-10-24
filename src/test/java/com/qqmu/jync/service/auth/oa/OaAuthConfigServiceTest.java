package com.qqmu.jync.service.auth.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.repository.OaAuthConfigRepository;
import com.qqmu.jync.util.CryptoUtil;

/**
 * The OA sign-in settings are a single row with hand-written validation: nothing on this form
 * may send a password anywhere but the URL the administrator typed, and an enabled config must
 * be complete enough for {@code HttpOaAuthClient} to call it.
 */
class OaAuthConfigServiceTest {

    private OaAuthConfigRepository repository;
    private OaAuthConfigService service;
    private CryptoUtil crypto;

    @BeforeEach
    void setUp() {
        repository = mock(OaAuthConfigRepository.class);
        when(repository.findById(OaAuthConfigService.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repository.save(any(OaAuthConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword("test-password");
        properties.setCryptoSalt("0011223344556677");
        crypto = new CryptoUtil(properties);
        service = new OaAuthConfigService(repository, crypto);
    }

    private OaAuthConfig enabled() {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(true);
        config.setLoginUrl("https://oa.example.com/api/login");
        config.setUserField("data.username");
        return config;
    }

    @Test
    void withNoStoredRowTheSettingsAreDisabledWithSafeDefaults() {
        OaAuthConfig config = service.load();

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getRequestFormat()).isEqualTo(OaAuthConfig.RequestFormat.FORM);
        assertThat(config.getUsernameField()).isEqualTo("username");
        assertThat(config.getPasswordField()).isEqualTo("password");
        assertThat(config.getUserField()).isEqualTo("username");
        assertThat(config.getTimeoutMs()).isEqualTo(5000);
        assertThat(config.isAllowInsecureHttp()).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void aRowStoredBeforeTheLocalSwitchExistedStillShowsLocalSignInAsOn() {
        // Upgrading adds the column as NULL, and NULL means "allowed". The settings page binds
        // this value to a checkbox: a null renders unchecked, so pressing save would silently
        // turn the local form off for an administrator who changed nothing.
        OaAuthConfig old = new OaAuthConfig();
        old.setId(OaAuthConfigService.SINGLETON_ID);
        old.setEnabled(true);
        old.setLocalLoginEnabled(null);
        when(repository.findById(OaAuthConfigService.SINGLETON_ID)).thenReturn(Optional.of(old));

        OaAuthConfig loaded = service.load();

        assertThat(loaded.getLocalLoginEnabled()).isEqualTo(Boolean.TRUE);
        assertThat(loaded.isLocalLoginAllowed()).isTrue();
    }

    @Test
    void enabledRequiresALoginUrl() {
        OaAuthConfig config = enabled();
        config.setLoginUrl("  ");

        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.url.required");
        verify(repository, never()).save(any());
    }

    @Test
    void onlyHttpAndHttpsUrlsAreAccepted() {
        OaAuthConfig config = enabled();
        config.setLoginUrl("file:///etc/passwd");

        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.url.invalid");
    }

    @Test
    void plainHttpNeedsExplicitConsent() {
        OaAuthConfig config = enabled();
        config.setLoginUrl("http://oa.internal/api/login");

        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.url.notHttps");

        config.setAllowInsecureHttp(true);
        assertThat(service.save(config).getLoginUrl()).isEqualTo("http://oa.internal/api/login");
    }

    @Test
    void enabledRequiresAResponseFieldForTheUserName() {
        OaAuthConfig config = enabled();
        config.setUserField("");

        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.userField.required");
    }

    @Test
    void successFieldAndValueMustComeTogether() {
        OaAuthConfig config = enabled();
        config.setSuccessField("code");

        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.success.incomplete");

        config.setSuccessValue("0");
        assertThat(service.save(config).getSuccessValue()).isEqualTo("0");
    }

    @Test
    void administratorFieldAndValueMustComeTogether() {
        OaAuthConfig config = enabled();
        config.setAdminField("data.isAdmin");

        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.admin.incomplete");

        config.setAdminValue("true");
        assertThat(service.save(config).getAdminField()).isEqualTo("data.isAdmin");
    }

    @Test
    void timeoutIsClampedToTheSupportedRange() {
        OaAuthConfig low = enabled();
        low.setTimeoutMs(10);
        assertThat(service.save(low).getTimeoutMs()).isEqualTo(OaAuthConfigService.MIN_TIMEOUT_MS);

        OaAuthConfig high = enabled();
        high.setTimeoutMs(120_000);
        assertThat(service.save(high).getTimeoutMs()).isEqualTo(OaAuthConfigService.MAX_TIMEOUT_MS);
    }

    @Test
    void blankFieldNamesFallBackToTheDefaults() {
        OaAuthConfig config = enabled();
        config.setUsernameField(" ");
        config.setPasswordField(null);

        OaAuthConfig saved = service.save(config);

        assertThat(saved.getUsernameField()).isEqualTo("username");
        assertThat(saved.getPasswordField()).isEqualTo("password");
    }

    @Test
    void valuesAreTrimmedAndTheRowIsAlwaysTheSingleton() {
        OaAuthConfig config = enabled();
        config.setId(99L);
        config.setLoginUrl("  https://oa.example.com/login  ");
        config.setUserField("  data.username  ");
        config.setAdminField("  data.admin  ");
        config.setAdminValue("  true , 1  ");

        OaAuthConfig saved = service.save(config);

        assertThat(saved.getId()).isEqualTo(OaAuthConfigService.SINGLETON_ID);
        assertThat(saved.getLoginUrl()).isEqualTo("https://oa.example.com/login");
        assertThat(saved.getUserField()).isEqualTo("data.username");
        assertThat(saved.getAdminField()).isEqualTo("data.admin");
        assertThat(saved.getAdminValue()).isEqualTo("true , 1");
    }

    @Test
    void turningLocalSignInOffWithoutAWorkingExternalMethodIsRejected() {
        // Otherwise this one save is a lockout: no local form and no external way in, with no
        // in-app way to undo it.
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(false);
        config.setLocalLoginEnabled(Boolean.FALSE);

        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.local.required");
        verify(repository, never()).save(any());
    }

    @Test
    void localSignInMayBeTurnedOffOnceExternalAuthIsEnabled() {
        OaAuthConfig config = enabled();
        config.setLocalLoginEnabled(Boolean.FALSE);

        assertThat(service.save(config).isLocalLoginAllowed()).isFalse();
    }

    @Test
    void theRescueSwitchPutsLocalSignInBackAndSavesIt() {
        OaAuthConfig stored = new OaAuthConfig();
        stored.setId(OaAuthConfigService.SINGLETON_ID);
        stored.setEnabled(true);
        stored.setLocalLoginEnabled(Boolean.FALSE);
        when(repository.findById(OaAuthConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(stored));

        assertThat(service.resetLocalLogin()).isTrue();
        assertThat(stored.getLocalLoginEnabled()).isEqualTo(Boolean.TRUE);
        verify(repository).save(stored);
    }

    @Test
    void theRescueSwitchChangesNothingWhenLocalSignInIsAlreadyAllowed() {
        OaAuthConfig stored = new OaAuthConfig();
        stored.setId(OaAuthConfigService.SINGLETON_ID);
        stored.setLocalLoginEnabled(Boolean.TRUE);
        when(repository.findById(OaAuthConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(stored));

        assertThat(service.resetLocalLogin()).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void theRescueSwitchCreatesNoRowWhenNothingIsStored() {
        assertThat(service.resetLocalLogin()).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void disablingDoesNotRequireAnyOtherField() {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(false);
        config.setLoginUrl("not a url");

        assertThat(service.save(config).isEnabled()).isFalse();
    }

    @Test
    void aRowWrittenBeforeTheModeColumnStillMeansPassword() {
        OaAuthConfig legacy = new OaAuthConfig();
        legacy.setMode(null);
        when(repository.findById(OaAuthConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(legacy));

        assertThat(service.load().getMode()).isEqualTo(OaAuthConfig.Mode.PASSWORD);
    }

    @Test
    void aRowWrittenBeforeTheLocalLoginColumnKeepsLocalLoginAllowed() {
        OaAuthConfig legacy = new OaAuthConfig();
        legacy.setLocalLoginEnabled(null);
        when(repository.findById(OaAuthConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(legacy));

        assertThat(service.load().isLocalLoginAllowed()).isTrue();
    }

    @Test
    void headerModeNeedsTheIdentityHeaderButNoPasswordEndpoint() {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.HEADER);

        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.userHeader.required");

        config.setUserHeader("X-Auth-User");
        assertThat(service.save(config).getUserHeader()).isEqualTo("X-Auth-User");
    }

    @Test
    void headerNamesMustBeTokensAndMayNotBeCookieOrAuthorization() {
        OaAuthConfig config = headerConfig();
        config.setUserHeader("X Auth User");
        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.header.invalid");

        config.setUserHeader("Cookie");
        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.header.invalid");
    }

    @Test
    void theSecretHeaderNameAndValueMustComeTogether() {
        OaAuthConfig config = headerConfig();
        config.setSecretHeaderName("X-Auth-Secret");

        assertThatThrownBy(() -> service.save(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.oa.secret.incomplete");
    }

    @Test
    void aTypedSecretIsEncryptedAndABlankOneKeepsTheStoredCiphertext() {
        OaAuthConfig config = headerConfig();
        config.setSecretHeaderName("X-Auth-Secret");

        OaAuthConfig saved = service.save(config, "s3cr3t", false);
        assertThat(saved.getSecretHeaderValue()).startsWith(CryptoUtil.PREFIX);
        assertThat(crypto.decrypt(saved.getSecretHeaderValue())).isEqualTo("s3cr3t");

        OaAuthConfig stored = new OaAuthConfig();
        stored.setSecretHeaderValue(saved.getSecretHeaderValue());
        when(repository.findById(OaAuthConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(stored));

        OaAuthConfig again = headerConfig();
        again.setSecretHeaderName("X-Auth-Secret");
        assertThat(service.save(again, "  ", false).getSecretHeaderValue())
                .isEqualTo(saved.getSecretHeaderValue());
    }

    @Test
    void clearingTheSecretRemovesIt() {
        OaAuthConfig config = headerConfig();
        config.setSecretHeaderName("X-Auth-Secret");

        assertThat(service.save(config, "s3cr3t", true).getSecretHeaderValue()).isNull();
    }

    @Test
    void ticketModeNeedsAValidationUrlAndAResponseField() {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.TICKET);

        assertThatThrownBy(() -> service.save(config))
                .hasMessage("error.oa.ticket.urlRequired");

        config.setValidateUrl("http://sso.internal/validate");
        assertThatThrownBy(() -> service.save(config))
                .hasMessage("error.oa.url.notHttps");

        config.setAllowInsecureHttp(true);
        assertThatThrownBy(() -> service.save(config))
                .hasMessage("error.oa.userField.required");

        config.setUserField("data.username");
        assertThat(service.save(config).getValidateMethod())
                .isEqualTo(OaAuthConfig.ValidateMethod.GET);
    }

    @Test
    void fieldsThatBelongToAnotherModeAreNotValidated() {
        OaAuthConfig config = headerConfig();
        config.setValidateUrl("not a url");
        config.setSuccessField("code");

        assertThat(service.save(config).getValidateUrl()).isEqualTo("not a url");
    }

    private OaAuthConfig headerConfig() {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.HEADER);
        config.setUserHeader("X-Auth-User");
        return config;
    }
}
