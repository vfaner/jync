package com.synctool.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import javax.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Locale resolution, with the emphasis on the single-request switch.
 *
 * <p>The regression these guard against: {@code ?lang=} used to need two clicks, because an
 * override that re-read the request cookie could not see the choice the interceptor had just
 * recorded on the response.
 */
class TimezoneAwareLocaleResolverTest {

    private static final String LANG_COOKIE = "SYNCTOOL_LANG";

    private TimezoneAwareLocaleResolver resolver() {
        TimezoneAwareLocaleResolver resolver = new TimezoneAwareLocaleResolver();
        resolver.setCookieName(LANG_COOKIE);
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        return resolver;
    }

    /** The core fix: switching away from the cookie's language takes effect on this response. */
    @Test
    void switchTakesEffectOnTheSameRequestThatRequestedIt() {
        TimezoneAwareLocaleResolver resolver = resolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(LANG_COOKIE, "zh-CN"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        // What LocaleChangeInterceptor does when it sees ?lang=en.
        resolver.setLocale(request, response, Locale.ENGLISH);

        // ...and what the view then renders with. Previously this was still zh-CN.
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
        assertThat(resolver.resolveLocaleContext(request).getLocale()).isEqualTo(Locale.ENGLISH);
    }

    /** The opposite direction, so the fix is not one-way. */
    @Test
    void switchBackToChineseAlsoTakesEffectImmediately() {
        TimezoneAwareLocaleResolver resolver = resolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(LANG_COOKIE, "en"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.setLocale(request, response, Locale.SIMPLIFIED_CHINESE);

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
    }

    /** A stored choice still wins over timezone inference on later requests. */
    @Test
    void languageCookieOutranksTimezone() {
        TimezoneAwareLocaleResolver resolver = resolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(LANG_COOKIE, "en"),
                new Cookie(TimezoneAwareLocaleResolver.TZ_COOKIE, "Asia/Shanghai"));

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }

    /** With no explicit choice, the reported timezone decides. */
    @Test
    void timezoneDecidesWhenNoLanguageCookie() {
        TimezoneAwareLocaleResolver resolver = resolver();

        MockHttpServletRequest chinese = new MockHttpServletRequest();
        chinese.setCookies(new Cookie(TimezoneAwareLocaleResolver.TZ_COOKIE, "Asia/Shanghai"));
        assertThat(resolver.resolveLocale(chinese)).isEqualTo(Locale.SIMPLIFIED_CHINESE);

        MockHttpServletRequest english = new MockHttpServletRequest();
        english.setCookies(new Cookie(TimezoneAwareLocaleResolver.TZ_COOKIE, "Europe/Berlin"));
        assertThat(resolver.resolveLocale(english)).isEqualTo(Locale.ENGLISH);
    }

    /** Timezone beats Accept-Language: the overseas-Chinese-user case the class exists for. */
    @Test
    void timezoneBeatsAcceptLanguageHeader() {
        TimezoneAwareLocaleResolver resolver = resolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addPreferredLocale(Locale.US);
        request.setCookies(new Cookie(TimezoneAwareLocaleResolver.TZ_COOKIE, "Asia/Shanghai"));

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
    }

    /** No cookies at all: fall back to the header, normalised to one of the two supported locales. */
    @Test
    void fallsBackToAcceptLanguageWhenNothingReported() {
        TimezoneAwareLocaleResolver resolver = resolver();

        MockHttpServletRequest german = new MockHttpServletRequest();
        german.addPreferredLocale(Locale.GERMANY);
        assertThat(resolver.resolveLocale(german)).isEqualTo(Locale.ENGLISH);

        MockHttpServletRequest taiwan = new MockHttpServletRequest();
        taiwan.addPreferredLocale(Locale.TRADITIONAL_CHINESE);
        assertThat(resolver.resolveLocale(taiwan)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
    }

    /** A malformed cookie must not surface as a 500; the request falls back instead. */
    @Test
    void malformedLanguageCookieFallsBackInsteadOfThrowing() {
        TimezoneAwareLocaleResolver resolver = resolver();
        // Matches production config, where invalid cookies are tolerated.
        resolver.setRejectInvalidCookies(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(LANG_COOKIE, "not a locale!!"),
                new Cookie(TimezoneAwareLocaleResolver.TZ_COOKIE, "Europe/Berlin"));

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }
}
