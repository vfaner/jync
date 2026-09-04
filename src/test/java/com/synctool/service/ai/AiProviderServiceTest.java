package com.synctool.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.synctool.config.SyncProperties;
import com.synctool.model.AiProtocol;
import com.synctool.model.AiProvider;
import com.synctool.repository.AiProviderRepository;
import com.synctool.util.CryptoUtil;

/**
 * Exercises the provider rules against a real JPA layer.
 *
 * <p>A mocked repository would not catch a malformed {@code @Query}, and the bulk
 * {@code disableAll()} update is the mechanism the "only one enabled" guarantee rests on,
 * so this runs against H2 rather than a stub.
 */
@DataJpaTest
class AiProviderServiceTest {

    @Autowired
    private AiProviderRepository repository;

    private AiProviderService service;

    /** Records what it was asked to probe and never touches the network. */
    private static class StubTestService extends AiConnectionTestService {

        StubTestService() {
            // No HTTP client: test() is overridden below, so a real one would only give this
            // stub the ability to make a request it must never make.
            super(null);
        }

        private AiProvider lastProvider;
        private String lastKey;
        private boolean succeed = true;

        @Override
        public TestResult test(AiProvider provider, String plainApiKey) {
            this.lastProvider = provider;
            this.lastKey = plainApiKey;
            return succeed
                    ? TestResult.success("OK", "http://stub", provider.getModel(), 1)
                    : TestResult.failure("HTTP 401", "http://stub", provider.getModel(), 1);
        }
    }

    private StubTestService probe;
    private SyncProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SyncProperties();
        properties.setCryptoPassword("test-key");
        properties.setCryptoSalt("5c0744940b5c369b");
        probe = new StubTestService();
        service = new AiProviderService(repository, probe, new CryptoUtil(properties), properties);
    }

    private AiProvider newProvider(String name) {
        AiProvider p = new AiProvider();
        p.setName(name);
        p.setProtocol(AiProtocol.OPENAI);
        p.setBaseUrl("https://api.example.com/v1");
        p.setModel("some-model");
        return p;
    }

    // --- exclusivity -------------------------------------------------------------------

    @Test
    void enablingOneProviderSwitchesOffTheOthers() {
        AiProvider first = service.save(newProvider("first"), "key-1");
        AiProvider second = service.save(newProvider("second"), "key-2");

        service.enable(first.getId());
        assertThat(service.activeProvider()).get().extracting(AiProvider::getName).isEqualTo("first");

        service.enable(second.getId());

        // The guarantee is a single active row, not merely that the new one is on.
        List<AiProvider> enabled = repository.findAll().stream()
                .filter(AiProvider::isEnabled).toList();
        assertThat(enabled).extracting(AiProvider::getName).containsExactly("second");
    }

    @Test
    void enablingTheSameProviderTwiceLeavesItEnabled() {
        // disableAll() clears every row including this one, so the re-read afterwards is
        // load-bearing; without it the second call would end with nothing enabled.
        AiProvider only = service.save(newProvider("only"), "key");

        service.enable(only.getId());
        service.enable(only.getId());

        assertThat(service.activeProvider()).get().extracting(AiProvider::getName).isEqualTo("only");
    }

    @Test
    void noProviderIsEnabledByDefault() {
        service.save(newProvider("fresh"), "key");
        assertThat(service.activeProvider()).isEmpty();
        assertThat(service.isAssistAvailable()).isFalse();
    }

    @Test
    void theGlobalSwitchHidesEvenAnEnabledProvider() {
        AiProvider p = service.save(newProvider("p"), "key");
        service.enable(p.getId());
        assertThat(service.isAssistAvailable()).isTrue();

        properties.getAi().setEnabled(false);

        // An air-gapped deployment must be able to shut this off without deleting rows.
        assertThat(service.activeProvider()).isEmpty();
        assertThat(service.isAssistAvailable()).isFalse();
    }

    // --- the key ------------------------------------------------------------------------

    @Test
    void theApiKeyIsStoredEncryptedAndDecryptedForUse() {
        AiProvider saved = service.save(newProvider("p"), "sk-secret");

        assertThat(saved.getApiKey()).isNotNull().isNotEqualTo("sk-secret").startsWith("enc:");
        assertThat(service.decryptKey(saved)).isEqualTo("sk-secret");
    }

    @Test
    void ablankKeyOnUpdateKeepsTheStoredOne() {
        AiProvider saved = service.save(newProvider("p"), "sk-secret");

        AiProvider edit = newProvider("p");
        edit.setId(saved.getId());
        edit.setMaxTokens(1024);
        AiProvider updated = service.save(edit, "");

        // The form never receives the secret, so a blank field must mean "unchanged" rather
        // than "erase it".
        assertThat(service.decryptKey(updated)).isEqualTo("sk-secret");
        assertThat(updated.getMaxTokens()).isEqualTo(1024);
    }

    @Test
    void aNewKeyOnUpdateReplacesTheStoredOne() {
        AiProvider saved = service.save(newProvider("p"), "sk-old");

        AiProvider edit = newProvider("p");
        edit.setId(saved.getId());
        AiProvider updated = service.save(edit, "sk-new");

        assertThat(service.decryptKey(updated)).isEqualTo("sk-new");
    }

    @Test
    void theProbeReceivesTheDecryptedKeyNotTheCiphertext() {
        AiProvider saved = service.save(newProvider("p"), "sk-secret");
        service.test(saved.getId());

        assertThat(probe.lastKey).isEqualTo("sk-secret");
    }

    // --- probe results ------------------------------------------------------------------

    @Test
    void aProbeResultIsRecordedForTheListBadge() {
        AiProvider saved = service.save(newProvider("p"), "key");
        assertThat(saved.getLastTestOk()).isNull();

        service.test(saved.getId());

        AiProvider reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getLastTestOk()).isTrue();
        assertThat(reloaded.getLastTestAt()).isNotNull();
    }

    @Test
    void aFailedProbeDoesNotUndoTheEnable() {
        // A network blip must not make the setting unsavable; the UI reports the red badge.
        probe.succeed = false;
        AiProvider saved = service.save(newProvider("p"), "key");

        AiProvider enabled = service.enable(saved.getId());

        assertThat(enabled.isEnabled()).isTrue();
        assertThat(enabled.getLastTestOk()).isFalse();
        assertThat(service.activeProvider()).isPresent();
    }

    @Test
    void editingTheEndpointClearsAStaleSuccessBadge() {
        AiProvider saved = service.save(newProvider("p"), "key");
        service.test(saved.getId());
        assertThat(repository.findById(saved.getId()).orElseThrow().getLastTestOk()).isTrue();

        AiProvider edit = newProvider("p");
        edit.setId(saved.getId());
        edit.setBaseUrl("https://elsewhere.example.com/v1");
        AiProvider updated = service.save(edit, "");

        // The old success proved nothing about the new URL.
        assertThat(updated.getLastTestOk()).isNull();
    }

    @Test
    void anUnrelatedEditKeepsTheBadge() {
        AiProvider saved = service.save(newProvider("p"), "key");
        service.test(saved.getId());

        AiProvider edit = newProvider("p");
        edit.setId(saved.getId());
        edit.setTimeoutSeconds(90);
        AiProvider updated = service.save(edit, "");

        assertThat(updated.getLastTestOk()).isTrue();
    }

    @Test
    void editingDoesNotSilentlyToggleTheEnabledFlag() {
        // The form has no enabled field, so a bound entity arrives with the default false.
        AiProvider saved = service.save(newProvider("p"), "key");
        service.enable(saved.getId());

        AiProvider edit = newProvider("p");
        edit.setId(saved.getId());
        service.save(edit, "");

        assertThat(service.activeProvider()).isPresent();
    }

    // --- validation ---------------------------------------------------------------------

    @Test
    void requiredFieldsAreRejectedWithAnI18nKey() {
        AiProvider noName = newProvider("  ");
        assertThatThrownBy(() -> service.save(noName, "k"))
                .hasMessage("error.ai.name.required");

        AiProvider noUrl = newProvider("p");
        noUrl.setBaseUrl("");
        assertThatThrownBy(() -> service.save(noUrl, "k"))
                .hasMessage("error.ai.baseUrl.required");

        AiProvider noModel = newProvider("p");
        noModel.setModel("");
        assertThatThrownBy(() -> service.save(noModel, "k"))
                .hasMessage("error.ai.model.required");
    }

    @Test
    void aBaseUrlWithoutASchemeIsRejected() {
        // Without this, URI.create succeeds and the failure surfaces as an opaque probe error.
        AiProvider p = newProvider("p");
        p.setBaseUrl("api.example.com/v1");
        assertThatThrownBy(() -> service.save(p, "k"))
                .hasMessage("error.ai.baseUrl.scheme");
    }

    @Test
    void aDuplicateNameIsRejectedButRenamingItselfIsNot() {
        service.save(newProvider("taken"), "k");
        assertThatThrownBy(() -> service.save(newProvider("taken"), "k"))
                .hasMessage("error.ai.name.duplicate");

        AiProvider other = service.save(newProvider("other"), "k");
        other.setName("other");
        assertThat(service.save(other, "")).isNotNull();
    }

    @Test
    void nonPositiveLimitsFallBackToDefaults() {
        AiProvider p = newProvider("p");
        p.setMaxTokens(0);
        p.setTimeoutSeconds(-5);

        AiProvider saved = service.save(p, "k");

        assertThat(saved.getMaxTokens()).isPositive();
        assertThat(saved.getTimeoutSeconds()).isPositive();
    }

    @Test
    void deletingAnEnabledProviderLeavesTheFeatureUnavailable() {
        AiProvider p = service.save(newProvider("p"), "k");
        service.enable(p.getId());

        service.delete(p.getId());

        assertThat(service.activeProvider()).isEmpty();
    }
}
