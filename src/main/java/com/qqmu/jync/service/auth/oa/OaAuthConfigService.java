package com.qqmu.jync.service.auth.oa;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.repository.OaAuthConfigRepository;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Reads and writes the single external-authentication settings row.
 *
 * <p>The row is shared by three modes ({@code PASSWORD}, {@code HEADER}, {@code TICKET}); only
 * the fields that belong to the selected mode are validated, so switching modes does not force
 * an administrator to fill in settings for a mode they are not using.
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
    private static final String DEFAULT_USER_FIELD = "username";
    private static final String DEFAULT_TICKET_PARAM = "ticket";

    /** RFC 7230 token: the only characters a header name may contain. */
    private static final Pattern HEADER_NAME =
            Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$");

    private final OaAuthConfigRepository repository;
    private final CryptoUtil crypto;

    public OaAuthConfigService(OaAuthConfigRepository repository, CryptoUtil crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    /** The stored settings, or an all-default disabled instance when nothing was saved yet. */
    @Transactional(readOnly = true)
    public OaAuthConfig load() {
        OaAuthConfig config = repository.findById(SINGLETON_ID).orElseGet(OaAuthConfig::new);
        if (config.getMode() == null) {
            config.setMode(OaAuthConfig.Mode.PASSWORD);
        }
        if (config.getMode() == OaAuthConfig.Mode.PASSWORD && config.getUserField() == null) {
            config.setUserField(DEFAULT_USER_FIELD);
        }
        if (config.getLocalLoginEnabled() == null) {
            // A row written before this column existed. Null means allowed everywhere else, but
            // the settings page renders it as an unchecked box, so leaving it null would have
            // the next save turn the local form off without the administrator touching it.
            config.setLocalLoginEnabled(Boolean.TRUE);
        }
        return config;
    }

    /** Whether the login page should offer the OA form. */
    @Transactional(readOnly = true)
    public boolean isEnabled() {
        return load().isEnabled();
    }

    @Transactional
    public OaAuthConfig save(OaAuthConfig config) {
        return save(config, null, false);
    }

    @Transactional
    public OaAuthConfig save(OaAuthConfig config, String newSecretValue, boolean clearSecret) {
        config.setId(SINGLETON_ID);
        config.setMode(config.getMode() == null ? OaAuthConfig.Mode.PASSWORD : config.getMode());
        config.setLoginUrl(trimToNull(config.getLoginUrl()));
        config.setUsernameField(orDefault(config.getUsernameField(), DEFAULT_USERNAME_FIELD));
        config.setPasswordField(orDefault(config.getPasswordField(), DEFAULT_PASSWORD_FIELD));
        config.setSuccessField(trimToNull(config.getSuccessField()));
        config.setSuccessValue(trimToNull(config.getSuccessValue()));
        config.setUserField(trimToNull(config.getUserField()));
        config.setAdminField(trimToNull(config.getAdminField()));
        config.setAdminValue(trimToNull(config.getAdminValue()));
        config.setAdminUsers(trimToNull(config.getAdminUsers()));
        config.setLoginEntryUrl(trimToNull(config.getLoginEntryUrl()));
        config.setLogoutRedirectUrl(trimToNull(config.getLogoutRedirectUrl()));
        config.setUserHeader(trimToNull(config.getUserHeader()));
        config.setAdminHeader(trimToNull(config.getAdminHeader()));
        config.setSecretHeaderName(trimToNull(config.getSecretHeaderName()));
        config.setValidateUrl(trimToNull(config.getValidateUrl()));
        config.setTicketParam(orDefault(config.getTicketParam(), DEFAULT_TICKET_PARAM));
        config.setValidateMethod(config.getValidateMethod() == null
                ? OaAuthConfig.ValidateMethod.GET : config.getValidateMethod());
        config.setLocalLoginEnabled(config.getLocalLoginEnabled() == null
                ? Boolean.TRUE : config.getLocalLoginEnabled());
        config.setRequestFormat(config.getRequestFormat() == null
                ? OaAuthConfig.RequestFormat.FORM : config.getRequestFormat());

        OaAuthConfig stored = repository.findById(SINGLETON_ID).orElse(null);
        if (clearSecret) {
            // A header name without a value is rejected below, so clearing removes the pair:
            // a half-configured shared secret must never look like one that is enforced.
            config.setSecretHeaderName(null);
            config.setSecretHeaderValue(null);
        } else {
            config.setSecretHeaderValue(resolveSecret(stored, newSecretValue));
        }

        int timeout = config.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : config.getTimeoutMs();
        config.setTimeoutMs(Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeout)));

        if (config.isEnabled()) {
            validateEnabled(config);
        }

        config.setUpdatedAt(Instant.now());
        return repository.save(config);
    }

    /**
     * The stored ciphertext survives a save that leaves the secret field blank, so opening the
     * settings page and pressing save does not silently turn the shared secret off.
     */
    private String resolveSecret(OaAuthConfig stored, String newSecretValue) {
        String typed = trimToNull(newSecretValue);
        if (typed != null) {
            return crypto.encrypt(typed);
        }
        return stored == null ? null : stored.getSecretHeaderValue();
    }

    private void validateEnabled(OaAuthConfig config) {
        validateOptionalEntryUrl(config.getLoginEntryUrl(), "error.oa.entryUrl.invalid");
        validateOptionalLogoutUrl(config.getLogoutRedirectUrl());
        switch (config.getMode()) {
            case PASSWORD -> validatePassword(config);
            case HEADER -> validateHeader(config);
            case TICKET -> validateTicket(config);
        }
    }

    private void validatePassword(OaAuthConfig config) {
        if (config.getLoginUrl() == null) {
            throw new IllegalArgumentException("error.oa.url.required");
        }
        validateHttpUrl(config.getLoginUrl(), config.isAllowInsecureHttp());
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

    private void validateHeader(OaAuthConfig config) {
        if (config.getUserHeader() == null) {
            throw new IllegalArgumentException("error.oa.userHeader.required");
        }
        validateHeaderName(config.getUserHeader());
        if (config.getAdminHeader() != null) {
            validateHeaderName(config.getAdminHeader());
        }
        if ((config.getAdminHeader() == null) != (config.getAdminValue() == null)) {
            throw new IllegalArgumentException("error.oa.admin.incomplete");
        }
        if ((config.getSecretHeaderName() == null) != (config.getSecretHeaderValue() == null)) {
            throw new IllegalArgumentException("error.oa.secret.incomplete");
        }
        if (config.getSecretHeaderName() != null) {
            validateHeaderName(config.getSecretHeaderName());
        }
    }

    private void validateTicket(OaAuthConfig config) {
        if (config.getValidateUrl() == null) {
            throw new IllegalArgumentException("error.oa.ticket.urlRequired");
        }
        validateHttpUrl(config.getValidateUrl(), config.isAllowInsecureHttp());
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

    private static void validateHeaderName(String name) {
        // Cookie and Authorization are present on ordinary browser requests; accepting either as
        // an identity header would turn every visitor's own cookie into a sign-in.
        if (!HEADER_NAME.matcher(name).matches()
                || name.equalsIgnoreCase("Cookie")
                || name.equalsIgnoreCase("Authorization")) {
            throw new IllegalArgumentException("error.oa.header.invalid");
        }
    }

    private static void validateHttpUrl(String value, boolean allowInsecureHttp) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("error.oa.url.invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("error.oa.url.invalid");
        }
        // Credentials in the URL would be logged by intermediate proxies and cannot be changed
        // from the settings page without a browser prompt.
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("error.oa.url.invalid");
        }
        if (scheme.equals("http") && !allowInsecureHttp) {
            throw new IllegalArgumentException("error.oa.url.notHttps");
        }
    }

    private static void validateOptionalEntryUrl(String value, String errorKey) {
        if (value == null) {
            return;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
                throw new IllegalArgumentException(errorKey);
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(errorKey);
        }
    }

    private static void validateOptionalLogoutUrl(String value) {
        if (value == null) {
            return;
        }
        if (value.startsWith("/") && !value.startsWith("//")) {
            return;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
                throw new IllegalArgumentException("error.oa.logoutUrl.invalid");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("error.oa.logoutUrl.invalid");
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
