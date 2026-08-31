package com.synctool.config;

import java.util.Locale;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the request locale, mirroring the reference project's precedence.
 *
 * <p>Order, highest priority first:
 * <ol>
 *   <li>An explicit user choice, persisted in the language cookie.
 *   <li>The browser's IANA timezone, reported by a small inline script on first visit. This is
 *       deliberately preferred over {@code Accept-Language}: an overseas Chinese user often runs
 *       an English-locale browser but sits in {@code Asia/Shanghai}, and the timezone is the
 *       better signal of which language they actually want.
 *   <li>{@code Accept-Language}, as the fallback when no timezone was reported.
 *   <li>Simplified Chinese, as the final default.
 * </ol>
 */
@Slf4j
public class TimezoneAwareLocaleResolver extends CookieLocaleResolver {

    /** Cookie the client writes its detected timezone into. */
    public static final String TZ_COOKIE = "SYNCTOOL_TZ";

    /**
     * Timezones treated as Chinese-speaking. Matches the reference project's list, which
     * includes Hong Kong, Macau and Taipei.
     */
    private static final Set<String> ZH_TIMEZONES = Set.of(
            "Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin",
            "Asia/Urumqi", "Asia/Kashgar", "Asia/Hong_Kong", "Asia/Macau",
            "Asia/Macao", "Asia/Taipei", "Asia/Beijing", "PRC", "ROC", "Hongkong");

    /**
     * Supplies the locale used when no language cookie is present.
     *
     * <p>This is deliberately the extension point rather than {@code resolveLocale}. The
     * superclass resolves in two steps: it parses the cookie once per request into a request
     * attribute, and {@code setLocale} — called by {@link LocaleChangeInterceptor} on
     * {@code ?lang=...} — overwrites that attribute at the same time as it queues the new
     * cookie on the response. Because the response cookie is not echoed back until the
     * <em>next</em> request, any override that reads {@code request.getCookies()} directly sees
     * the stale value and renders the old language, which is why switching used to need a second
     * click or a refresh. Hooking in here leaves that attribute handshake intact, so a single
     * click takes effect on the very response it triggers.
     */
    @Override
    protected Locale determineDefaultLocale(HttpServletRequest request) {
        String timezone = readTimezone(request);
        if (timezone != null) {
            Locale byTimezone = ZH_TIMEZONES.contains(timezone)
                    ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
            log.debug("Locale {} inferred from timezone {}", byTimezone, timezone);
            return byTimezone;
        }

        // No timezone reported yet (first request, before the script runs): fall back to the
        // header rather than guessing.
        Locale fromHeader = request.getLocale();
        if (fromHeader != null && !fromHeader.getLanguage().isBlank()) {
            return fromHeader.getLanguage().toLowerCase().startsWith("zh")
                    ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        }

        Locale configured = getDefaultLocale();
        return configured != null ? configured : Locale.SIMPLIFIED_CHINESE;
    }

    /**
     * Narrows a parsed cookie value to a language this app actually ships.
     *
     * <p>{@code StringUtils.parseLocale} is lenient — it turns junk like {@code "not a locale!!"}
     * into a {@code Locale} whose language is {@code "not"}, which would otherwise be handed
     * straight to Thymeleaf and silently resolve every message to its fallback. Anything that is
     * not recognisably Chinese or English is treated as absent so the timezone/header chain runs.
     */
    @Override
    protected Locale parseLocaleValue(String localeValue) {
        Locale parsed = super.parseLocaleValue(localeValue);
        if (parsed == null) {
            return null;
        }
        String language = parsed.getLanguage().toLowerCase();
        if (language.startsWith("zh")) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if (language.equals("en")) {
            return Locale.ENGLISH;
        }
        log.debug("Ignoring unsupported locale cookie value '{}'", localeValue);
        return null;
    }

    private String readTimezone(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (javax.servlet.http.Cookie cookie : request.getCookies()) {
            if (TZ_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }
}
