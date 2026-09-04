package com.synctool.service.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.synctool.config.SyncProperties;
import com.synctool.model.AiProtocol;
import com.synctool.model.AiProvider;
import com.synctool.repository.AiProviderRepository;
import com.synctool.util.CryptoUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * CRUD for AI providers, plus the rule that at most one is enabled.
 *
 * <p>Enabling one switches off whatever was on. That is enforced here inside a transaction
 * rather than by disabling buttons in the browser: two open tabs, or a stale page, would
 * otherwise be able to leave two rows enabled and make {@link #activeProvider()} arbitrary.
 */
@Service
@Slf4j
public class AiProviderService {

    private final AiProviderRepository repository;
    private final AiConnectionTestService testService;
    private final CryptoUtil cryptoUtil;
    private final SyncProperties properties;

    public AiProviderService(AiProviderRepository repository,
                             AiConnectionTestService testService,
                             CryptoUtil cryptoUtil,
                             SyncProperties properties) {
        this.repository = repository;
        this.testService = testService;
        this.cryptoUtil = cryptoUtil;
        this.properties = properties;
    }

    public List<AiProvider> findAll() {
        return repository.findAllByOrderByNameAsc();
    }

    public Optional<AiProvider> findById(Long id) {
        return repository.findById(id);
    }

    /**
     * The provider that AI-assisted conversion should use, if any.
     *
     * <p>Empty when the feature is switched off globally or nothing is enabled. Callers treat
     * empty as "the feature is unavailable", which is what keeps a stock install from making
     * any outbound request at all.
     */
    public Optional<AiProvider> activeProvider() {
        if (!properties.getAi().isEnabled()) {
            return Optional.empty();
        }
        return repository.findFirstByEnabledTrue();
    }

    /** Whether AI-assisted conversion is available right now. */
    public boolean isAssistAvailable() {
        return activeProvider().isPresent();
    }

    /**
     * Creates or updates a provider.
     *
     * <p>A blank key on an update means "keep the stored one", so the edit form never has to
     * round-trip the secret to the browser -- the same contract the database password uses.
     */
    @Transactional
    public AiProvider save(AiProvider provider, String rawApiKey) {
        validate(provider);

        if (provider.getId() != null) {
            AiProvider existing = repository.findById(provider.getId())
                    .orElseThrow(() -> new IllegalArgumentException("error.ai.provider.missing"));
            if (rawApiKey == null || rawApiKey.isEmpty()) {
                provider.setApiKey(existing.getApiKey());
            } else {
                provider.setApiKey(cryptoUtil.encrypt(rawApiKey));
            }
            provider.setCreatedAt(existing.getCreatedAt());
            // Editing the endpoint invalidates what the last probe proved, so the badge is
            // cleared rather than left claiming a stale success.
            if (endpointChanged(existing, provider) || (rawApiKey != null && !rawApiKey.isEmpty())) {
                provider.setLastTestAt(null);
                provider.setLastTestOk(null);
                provider.setLastTestMessage(null);
            } else {
                provider.setLastTestAt(existing.getLastTestAt());
                provider.setLastTestOk(existing.getLastTestOk());
                provider.setLastTestMessage(existing.getLastTestMessage());
            }
            provider.setEnabled(existing.isEnabled());
        } else {
            provider.setApiKey(cryptoUtil.encrypt(rawApiKey));
            provider.setEnabled(false);
        }

        AiProvider saved = repository.save(provider);
        log.info("Saved AI provider '{}' ({} {})",
                saved.getName(), saved.getProtocol(), saved.getModel());
        return saved;
    }

    private boolean endpointChanged(AiProvider existing, AiProvider updated) {
        return !java.util.Objects.equals(existing.getBaseUrl(), updated.getBaseUrl())
                || !java.util.Objects.equals(existing.getModel(), updated.getModel())
                || existing.getProtocol() != updated.getProtocol();
    }

    private void validate(AiProvider provider) {
        if (provider.getName() == null || provider.getName().isBlank()) {
            throw new IllegalArgumentException("error.ai.name.required");
        }
        provider.setName(provider.getName().trim());
        repository.findByName(provider.getName()).ifPresent(existing -> {
            if (!existing.getId().equals(provider.getId())) {
                throw new IllegalArgumentException("error.ai.name.duplicate");
            }
        });

        if (provider.getProtocol() == null) {
            provider.setProtocol(AiProtocol.OPENAI);
        }
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("error.ai.baseUrl.required");
        }
        provider.setBaseUrl(provider.getBaseUrl().trim());
        if (!provider.getBaseUrl().startsWith("http://")
                && !provider.getBaseUrl().startsWith("https://")) {
            throw new IllegalArgumentException("error.ai.baseUrl.scheme");
        }
        if (provider.getModel() == null || provider.getModel().isBlank()) {
            throw new IllegalArgumentException("error.ai.model.required");
        }
        provider.setModel(provider.getModel().trim());

        if (provider.getMaxTokens() == null || provider.getMaxTokens() <= 0) {
            provider.setMaxTokens(4096);
        }
        if (provider.getTimeoutSeconds() == null || provider.getTimeoutSeconds() <= 0) {
            provider.setTimeoutSeconds(60);
        }
    }

    /**
     * Switches this provider on and every other one off.
     *
     * <p>A probe runs immediately afterwards and its result is recorded, but a failed probe
     * does not undo the enable: a transient network problem should not make the setting
     * unsavable. The list shows the failure as a red badge instead.
     */
    @Transactional
    public AiProvider enable(Long id) {
        AiProvider provider = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.ai.provider.missing"));
        repository.disableAll();
        // Re-read: disableAll() was a bulk update, so the instance above may now be stale.
        provider = repository.findById(id).orElseThrow();
        provider.setEnabled(true);
        AiProvider saved = repository.save(provider);
        log.info("Enabled AI provider '{}'; all others switched off", saved.getName());
        recordProbe(saved);
        return saved;
    }

    @Transactional
    public void disable(Long id) {
        repository.findById(id).ifPresent(provider -> {
            provider.setEnabled(false);
            repository.save(provider);
            log.info("Disabled AI provider '{}'", provider.getName());
        });
    }

    @Transactional
    public void delete(Long id) {
        repository.findById(id).ifPresent(provider -> {
            repository.deleteById(id);
            log.info("Deleted AI provider '{}'", provider.getName());
        });
    }

    /** Probes a saved provider and stores the outcome for the list badge. */
    @Transactional
    public AiConnectionTestService.TestResult test(Long id) {
        AiProvider provider = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.ai.provider.missing"));
        return recordProbe(provider);
    }

    private AiConnectionTestService.TestResult recordProbe(AiProvider provider) {
        AiConnectionTestService.TestResult result =
                testService.test(provider, decryptKey(provider));
        provider.setLastTestAt(Instant.now());
        provider.setLastTestOk(result.isSuccess());
        provider.setLastTestMessage(abbreviate(result.getMessage()));
        repository.save(provider);
        return result;
    }

    /**
     * Probes settings that have not been saved yet.
     *
     * <p>Lets the user verify a key and model before committing them, which is where most
     * setup mistakes are made and the reason the database form has the same affordance.
     */
    public AiConnectionTestService.TestResult testTransient(AiProvider provider, String rawApiKey) {
        String key = rawApiKey;
        if ((key == null || key.isEmpty()) && provider.getId() != null) {
            key = repository.findById(provider.getId())
                    .map(this::decryptKey)
                    .orElse(null);
        }
        return testService.test(provider, key);
    }

    /** Decrypts the stored key. Returns null when decryption fails so the probe reports 401. */
    public String decryptKey(AiProvider provider) {
        try {
            return cryptoUtil.decrypt(provider.getApiKey());
        } catch (IllegalStateException e) {
            // The crypto key changed since the provider was saved. A clear 401 from the
            // endpoint plus this log is more useful than failing before sending anything.
            log.warn("Could not decrypt the API key for AI provider '{}'; re-enter it",
                    provider.getName());
            return null;
        }
    }

    private String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
