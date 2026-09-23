package com.qqmu.jync.config;

import java.util.Locale;
import java.util.Set;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.util.WebUtils;

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
    public static final String TZ_COOKIE = "JYNC_TZ";

    /** Cookie an explicit language choice is persisted in; wired up by {@link I18nConfig}. */
    public static final String LANG_COOKIE = "JYNC_LANG";

    /** Pre-rename (SyncTool &lt;= 1.2.x) cookie names, still honoured on upgrade. */
    static final String LEGACY_TZ_COOKIE = "SYNCTOOL_TZ";
    static final String LEGACY_LANG_COOKIE = "SYNCTOOL_LANG";

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
        // Upgrade from SyncTool (<= 1.2.x): honour the language choice persisted in the
        // legacy cookie. This method only runs when the renamed cookie is absent, so the
        // new cookie always wins once the browser has picked it up.
        Locale legacy = parseLocaleLeniently(readCookie(request, LEGACY_LANG_COOKIE));
        if (legacy != null) {
            return legacy;
        }

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

    /**
     * {@code parseLocaleValue} for cookies the base class does not guard.
     *
     * <p>{@code CookieLocaleResolver} catches {@link IllegalArgumentException} around the
     * <em>renamed</em> cookie when {@code rejectInvalidCookies} is false, but
     * {@code determineDefaultLocale} — where the legacy cookie is read — runs outside that
     * try/catch. A legacy value like {@code zh_CN.UTF-8} (a perfectly ordinary POSIX locale
     * string) makes {@code StringUtils.parseLocale} throw, which would 500 every request the
     * browser sends with that cookie. Degrade to "absent" instead, exactly like the base
     * class degrades a malformed new cookie.
     */
    private Locale parseLocaleLeniently(String localeValue) {
        if (localeValue == null) {
            return null;
        }
        try {
            return parseLocaleValue(localeValue);
        } catch (IllegalArgumentException e) {
            log.debug("Ignoring unparseable legacy language cookie value '{}'", localeValue);
            return null;
        }
    }

    private String readTimezone(HttpServletRequest request) {
        String timezone = readCookie(request, TZ_COOKIE);
        // Pre-rename browsers keep reporting into SYNCTOOL_TZ until theme.js rewrites it.
        return timezone != null ? timezone : readCookie(request, LEGACY_TZ_COOKIE);
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie cookie = WebUtils.getCookie(request, name);
        if (cookie == null) {
            return null;
        }
        String value = cookie.getValue();
        return value == null || value.isBlank() ? null : value.trim();
    }
}
