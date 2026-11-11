package com.qqmu.jync.service.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.AlertConfigForm;
import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AlertWebhookFormat;
import com.qqmu.jync.repository.AlertConfigRepository;
import com.qqmu.jync.util.CryptoUtil;

/**
 * The alerting settings row: defaults, validation, and the webhook URL's stay-at-rest rules.
 *
 * <p>The URL is a bearer credential — anyone holding it can post into the chat room — so it is
 * encrypted like a database password, never handed back to the settings page, and the page is
 * shown only its tail.
 */
@ExtendWith(MockitoExtension.class)
class AlertConfigServiceTest {

    @Mock private AlertConfigRepository repository;

    private CryptoUtil crypto;
    private AlertConfigService service;

    @BeforeEach
    void setUp() {
        crypto = new CryptoUtil(new SyncProperties());
        service = new AlertConfigService(repository, crypto);
    }

    private static AlertConfigForm form(boolean enabled) {
        AlertConfigForm form = new AlertConfigForm();
        form.setEnabled(enabled);
        form.setFormat(AlertWebhookFormat.GENERIC);
        form.setCooldownMinutes(30);
        form.setCategories(List.of("SYNC_FAILURE", "POLL_STUCK"));
        return form;
    }

    @Test
    void aFreshInstallStartsDisabledWithEveryCategorySelected() {
        when(repository.findById(AlertConfigService.SINGLETON_ID)).thenReturn(Optional.empty());

        AlertConfig config = service.load();

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getFormat()).isEqualTo(AlertWebhookFormat.GENERIC);
        assertThat(config.getCooldownMinutes()).isEqualTo(30);
        // A new installation that flips only the switch gets all seven checks, not a silent
        // "enabled but nothing selected".
        assertThat(config.enabledCategorySet()).containsExactlyInAnyOrder(AlertCategory.values());
        assertThat(service.webhookUrlOrNull()).isNull();
    }

    @Test
    void savingAnEnabledConfigWithoutAUrlIsRefused() {
        when(repository.findById(AlertConfigService.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(form(true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.alert.url.required");
    }

    @Test
    void aSavedUrlIsEncryptedAndOnlyItsTailIsEverShown() {
        when(repository.findById(AlertConfigService.SINGLETON_ID)).thenReturn(Optional.empty());
        AlertConfigForm form = form(true);
        form.setWebhookUrl("https://hooks.example.com/services/T000/B000/secret-token-1234");

        service.save(form);

        ArgumentCaptor<AlertConfig> saved = ArgumentCaptor.forClass(AlertConfig.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getWebhookUrl()).startsWith(CryptoUtil.PREFIX);
        assertThat(saved.getValue().getWebhookUrl()).doesNotContain("secret-token");
        when(repository.findById(AlertConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(saved.getValue()));

        assertThat(service.webhookUrlOrNull())
                .isEqualTo("https://hooks.example.com/services/T000/B000/secret-token-1234");
        assertThat(service.maskedUrl()).endsWith("1234");
        assertThat(service.maskedUrl()).doesNotContain("secret-token");
    }

    @Test
    void aBlankUrlOnResaveKeepsTheStoredSecret() {
        AlertConfig stored = new AlertConfig();
        stored.setId(AlertConfigService.SINGLETON_ID);
        stored.setEnabled(true);
        stored.setWebhookUrl(crypto.encrypt("https://hooks.example.com/keep-me-9999"));
        when(repository.findById(AlertConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(stored));

        AlertConfigForm form = form(true);
        form.setWebhookUrl("   ");
        service.save(form);

        ArgumentCaptor<AlertConfig> saved = ArgumentCaptor.forClass(AlertConfig.class);
        verify(repository).save(saved.capture());
        assertThat(crypto.decrypt(saved.getValue().getWebhookUrl()))
                .isEqualTo("https://hooks.example.com/keep-me-9999");
    }

    @Test
    void clearingTheUrlRemovesIt() {
        AlertConfig stored = new AlertConfig();
        stored.setId(AlertConfigService.SINGLETON_ID);
        stored.setWebhookUrl(crypto.encrypt("https://hooks.example.com/gone-1111"));
        when(repository.findById(AlertConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(stored));

        AlertConfigForm form = form(false);
        form.setClearWebhookUrl(true);
        service.save(form);

        ArgumentCaptor<AlertConfig> saved = ArgumentCaptor.forClass(AlertConfig.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getWebhookUrl()).isNull();
    }

    @Test
    void aTypedUrlWinsOverTheClearCheckbox() {
        // The "clear the stored URL" checkbox stays visible while a replacement is typed.
        // Letting the checkbox win silently discarded the new URL and left an enabled config
        // with no webhook, so notifications stopped without any error on the page.
        AlertConfig stored = new AlertConfig();
        stored.setId(AlertConfigService.SINGLETON_ID);
        stored.setEnabled(true);
        stored.setWebhookUrl(crypto.encrypt("https://hooks.example.com/old-0000"));
        when(repository.findById(AlertConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(stored));

        AlertConfigForm form = form(true);
        form.setWebhookUrl("https://hooks.example.com/new-2222");
        form.setClearWebhookUrl(true);
        service.save(form);

        ArgumentCaptor<AlertConfig> saved = ArgumentCaptor.forClass(AlertConfig.class);
        verify(repository).save(saved.capture());
        assertThat(crypto.decrypt(saved.getValue().getWebhookUrl()))
                .isEqualTo("https://hooks.example.com/new-2222");
    }

    @Test
    void anUndecryptableStoredUrlReadsAsNoneOnTheSettingsPage() {
        // The key file was replaced, or sync.crypto-* was changed after the URL was saved: the
        // ciphertext belongs to a key this instance no longer holds. The settings page must
        // still open — that is where the address is re-entered — so the masked tail reads as
        // "none stored". The send path stays strict: its callers decide what a failure means.
        AlertConfig stored = new AlertConfig();
        stored.setId(AlertConfigService.SINGLETON_ID);
        stored.setWebhookUrl(new CryptoUtil(cryptoProperties("another-password", "0011223344556677"))
                .encrypt("https://hooks.example.com/lost-7777"));
        when(repository.findById(AlertConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(stored));

        assertThat(service.maskedUrl()).isNull();
        assertThatThrownBy(service::webhookUrlOrNull).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enablingWithOnlyAnUndecryptableStoredUrlIsRefused() {
        // "Unreadable" is the same as "none" on save too: flashing an enabled config whose URL
        // nothing can decrypt would silently stop every notification with no error on the page.
        AlertConfig stored = new AlertConfig();
        stored.setId(AlertConfigService.SINGLETON_ID);
        stored.setWebhookUrl(new CryptoUtil(cryptoProperties("another-password", "0011223344556677"))
                .encrypt("https://hooks.example.com/lost-8888"));
        when(repository.findById(AlertConfigService.SINGLETON_ID))
                .thenReturn(Optional.of(stored));

        AlertConfigForm form = form(true);
        form.setWebhookUrl("   ");

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.alert.url.required");
    }

    private static SyncProperties cryptoProperties(String password, String salt) {
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword(password);
        properties.setCryptoSalt(salt);
        return properties;
    }

    @Test
    void anInvalidUrlSchemeIsRefused() {
        when(repository.findById(AlertConfigService.SINGLETON_ID)).thenReturn(Optional.empty());
        AlertConfigForm form = form(true);
        form.setWebhookUrl("ftp://hooks.example.com/x");

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.alert.url.invalid");
    }

    @Test
    void anEnabledConfigNeedsAtLeastOneCategory() {
        when(repository.findById(AlertConfigService.SINGLETON_ID)).thenReturn(Optional.empty());
        AlertConfigForm form = form(true);
        form.setWebhookUrl("https://hooks.example.com/x");
        form.setCategories(List.of());

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.alert.categories.required");
    }

    @Test
    void anUnknownCategoryIsRefused() {
        when(repository.findById(AlertConfigService.SINGLETON_ID)).thenReturn(Optional.empty());
        AlertConfigForm form = form(false);
        form.setCategories(List.of("SYNC_FAILURE", "NOT_A_CATEGORY"));

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.alert.categories.invalid");
    }

    @Test
    void theCooldownIsLimitedToItsAllowedRange() {
        when(repository.findById(AlertConfigService.SINGLETON_ID)).thenReturn(Optional.empty());
        AlertConfigForm tooShort = form(false);
        tooShort.setCooldownMinutes(0);

        assertThatThrownBy(() -> service.save(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.alert.cooldown");
    }

    @Test
    void categoriesRoundTripThroughTheStoredCsv() {
        AlertConfig config = new AlertConfig();
        config.setCategories("POLL_STUCK,DATA_DRIFT");

        assertThat(config.enabledCategorySet())
                .containsExactlyInAnyOrder(AlertCategory.POLL_STUCK, AlertCategory.DATA_DRIFT);
        assertThat(config.enabledCategorySet()).doesNotContain(AlertCategory.SYNC_FAILURE);
    }

    @Test
    void aLegacyRowWithoutCategoriesMeansEveryCategory() {
        AlertConfig config = new AlertConfig();
        config.setCategories(null);

        assertThat(config.enabledCategorySet()).containsExactlyInAnyOrder(AlertCategory.values());
        verify(repository, org.mockito.Mockito.never()).save(any());
    }
}
