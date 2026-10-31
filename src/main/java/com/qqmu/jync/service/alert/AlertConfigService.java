package com.qqmu.jync.service.alert;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.qqmu.jync.dto.AlertConfigForm;
import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AlertWebhookFormat;
import com.qqmu.jync.repository.AlertConfigRepository;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Reads and writes the single alerting settings row.
 *
 * <p>Validation throws i18n keys rather than binding errors: the settings page shows them
 * inline, and the evaluator must never start posting to a half-configured endpoint.
 */
@Service
public class AlertConfigService {

    /** Fixed primary key; the table is a settings singleton. */
    public static final long SINGLETON_ID = 1L;

    public static final int DEFAULT_COOLDOWN_MINUTES = 30;
    public static final int MIN_COOLDOWN_MINUTES = 5;
    public static final int MAX_COOLDOWN_MINUTES = 1440;

    /** Long enough for a real webhook URL; short enough not to store an unbounded blob. */
    static final int MAX_URL_LENGTH = 2048;

    private final AlertConfigRepository repository;
    private final CryptoUtil crypto;

    public AlertConfigService(AlertConfigRepository repository, CryptoUtil crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    /** The stored settings, or an all-default disabled instance when nothing was saved yet. */
    @Transactional(readOnly = true)
    public AlertConfig load() {
        AlertConfig config = repository.findById(SINGLETON_ID).orElseGet(AlertConfig::new);
        if (config.getFormat() == null) {
            config.setFormat(AlertWebhookFormat.GENERIC);
        }
        if (config.getCooldownMinutes() == null) {
            config.setCooldownMinutes(DEFAULT_COOLDOWN_MINUTES);
        }
        return config;
    }

    /** Whether alert delivery is switched on and has somewhere to go. */
    @Transactional(readOnly = true)
    public boolean isDeliverable() {
        AlertConfig config = load();
        return config.isEnabled() && webhookUrlOrNull() != null;
    }

    /** The decrypted URL the notifier posts to, or null when none is stored. */
    @Transactional(readOnly = true)
    public String webhookUrlOrNull() {
        AlertConfig config = repository.findById(SINGLETON_ID).orElse(null);
        return config == null ? null : decryptOrNull(config.getWebhookUrl());
    }

    /** The tail of the saved URL, for the settings page; null when none is stored. */
    @Transactional(readOnly = true)
    public String maskedUrl() {
        String url = webhookUrlOrNull();
        if (url == null) {
            return null;
        }
        String tail = url.length() <= 4 ? url : url.substring(url.length() - 4);
        return "••••" + tail;
    }

    /**
     * Stores the submitted settings, keeping the stored URL when the form left it blank.
     *
     * @throws IllegalArgumentException carrying an i18n key when the input is unusable
     */
    @Transactional
    public void save(AlertConfigForm form) {
        AlertConfig config = repository.findById(SINGLETON_ID).orElseGet(() -> {
            AlertConfig created = new AlertConfig();
            created.setId(SINGLETON_ID);
            return created;
        });

        String typed = StringUtils.hasText(form.getWebhookUrl())
                ? form.getWebhookUrl().trim() : null;
        if (typed != null) {
            validateUrl(typed);
        }
        boolean willHaveUrl = typed != null || (!form.isClearWebhookUrl()
                && config.getWebhookUrl() != null && !config.getWebhookUrl().isEmpty());
        if (form.isEnabled() && !willHaveUrl) {
            throw new IllegalArgumentException("error.alert.url.required");
        }

        Set<AlertCategory> categories = parseCategories(form.getCategories());
        if (form.isEnabled() && categories.isEmpty()) {
            throw new IllegalArgumentException("error.alert.categories.required");
        }
        int cooldown = form.getCooldownMinutes() == null
                ? DEFAULT_COOLDOWN_MINUTES : form.getCooldownMinutes();
        if (cooldown < MIN_COOLDOWN_MINUTES || cooldown > MAX_COOLDOWN_MINUTES) {
            throw new IllegalArgumentException("error.alert.cooldown");
        }

        config.setEnabled(form.isEnabled());
        config.setFormat(form.getFormat() == null
                ? AlertWebhookFormat.GENERIC : form.getFormat());
        config.setCategories(AlertCategory.toCsv(categories));
        config.setCooldownMinutes(cooldown);
        if (form.isClearWebhookUrl()) {
            config.setWebhookUrl(null);
        } else if (typed != null) {
            config.setWebhookUrl(crypto.encrypt(typed));
        }
        config.setUpdatedAt(Instant.now());
        repository.save(config);
    }

    private static Set<AlertCategory> parseCategories(List<String> names) {
        Set<AlertCategory> categories = EnumSet.noneOf(AlertCategory.class);
        if (names == null) {
            return categories;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                categories.add(AlertCategory.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("error.alert.categories.invalid");
            }
        }
        return categories;
    }

    private static void validateUrl(String url) {
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("error.alert.url.invalid");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null
                    ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("error.alert.url.invalid");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("error.alert.url.invalid");
        }
    }

    private String decryptOrNull(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        return crypto.decrypt(stored);
    }

    /** Every category, in declaration order — the order the page lists its checkboxes. */
    public static List<AlertCategory> allCategories() {
        return new ArrayList<>(List.of(AlertCategory.values()));
    }
}
