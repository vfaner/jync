package com.qqmu.jync.service.auth.oa;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.repository.OaAuthConfigRepository;

/**
 * Reads and writes the single OA sign-in settings row.
 *
 * <p>Validation is hand-written and throws message keys rather than binding errors: the settings
 * page shows them inline, and the login path must never start a call to a half-configured
 * endpoint.
 */
@Service
public class OaAuthConfigService {

    /** Fixed primary key; the table is a settings singleton. */
    public static final long SINGLETON_ID = 1L;

    public static final int DEFAULT_TIMEOUT_MS = 5000;
    public static final int MIN_TIMEOUT_MS = 500;
    public static final int MAX_TIMEOUT_MS = 30_000;

    private static final String DEFAULT_USERNAME_FIELD = "username";
    private static final String DEFAULT_PASSWORD_FIELD = "password";

    private final OaAuthConfigRepository repository;

    public OaAuthConfigService(OaAuthConfigRepository repository) {
        this.repository = repository;
    }

    /** The stored settings, or an all-default disabled instance when nothing was saved yet. */
    @Transactional(readOnly = true)
    public OaAuthConfig load() {
        Optional<OaAuthConfig> stored = repository.findById(SINGLETON_ID);
        return stored.orElseGet(OaAuthConfig::new);
    }

    /** Whether the login page should offer the OA form. */
    @Transactional(readOnly = true)
    public boolean isEnabled() {
        return load().isEnabled();
    }

    @Transactional
    public OaAuthConfig save(OaAuthConfig config) {
        config.setId(SINGLETON_ID);

        config.setLoginUrl(trimToNull(config.getLoginUrl()));
        config.setUsernameField(orDefault(config.getUsernameField(), DEFAULT_USERNAME_FIELD));
        config.setPasswordField(orDefault(config.getPasswordField(), DEFAULT_PASSWORD_FIELD));
        config.setSuccessField(trimToNull(config.getSuccessField()));
        config.setSuccessValue(trimToNull(config.getSuccessValue()));
        config.setUserField(trimToNull(config.getUserField()));
        config.setAdminField(trimToNull(config.getAdminField()));
        config.setAdminValue(trimToNull(config.getAdminValue()));
        config.setRequestFormat(config.getRequestFormat() == null
                ? OaAuthConfig.RequestFormat.FORM : config.getRequestFormat());

        int timeout = config.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : config.getTimeoutMs();
        config.setTimeoutMs(Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeout)));

        if (config.isEnabled()) {
            validateEnabled(config);
        }

        config.setUpdatedAt(Instant.now());
        return repository.save(config);
    }

    private void validateEnabled(OaAuthConfig config) {
        if (config.getLoginUrl() == null) {
            throw new IllegalArgumentException("error.oa.url.required");
        }
        URI uri;
        try {
            uri = URI.create(config.getLoginUrl());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("error.oa.url.invalid");
        }
        String scheme = uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("error.oa.url.invalid");
        }
        // Credentials in the URL would be logged by intermediate proxies and cannot be changed
        // from the settings page without a browser prompt.
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("error.oa.url.invalid");
        }
        if (scheme.equals("http") && !config.isAllowInsecureHttp()) {
            throw new IllegalArgumentException("error.oa.url.notHttps");
        }
        if (config.getUserField() == null) {
            throw new IllegalArgumentException("error.oa.userField.required");
        }
        if ((config.getSuccessField() == null) != (config.getSuccessValue() == null)) {
            throw new IllegalArgumentException("error.oa.success.incomplete");
        }
        if ((config.getAdminField() == null) != (config.getAdminValue() == null)) {
            throw new IllegalArgumentException("error.oa.admin.incomplete");
        }
    }

    private static String orDefault(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
