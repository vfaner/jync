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

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.repository.OaAuthConfigRepository;

/**
 * The OA sign-in settings are a single row with hand-written validation: nothing on this form
 * may send a password anywhere but the URL the administrator typed, and an enabled config must
 * be complete enough for {@code HttpOaAuthClient} to call it.
 */
class OaAuthConfigServiceTest {

    private OaAuthConfigRepository repository;
    private OaAuthConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(OaAuthConfigRepository.class);
        when(repository.findById(OaAuthConfigService.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repository.save(any(OaAuthConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new OaAuthConfigService(repository);
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
    void disablingDoesNotRequireAnyOtherField() {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(false);
        config.setLoginUrl("not a url");

        assertThat(service.save(config).isEnabled()).isFalse();
    }
}
