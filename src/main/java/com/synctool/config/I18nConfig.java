package com.synctool.config;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Internationalization. {@code ?lang=zh_CN} switches locale and the choice is persisted in a
 * cookie so it survives navigation and restarts.
 *
 * <p>Default-language selection follows the reference project: the browser timezone is the
 * primary signal, {@code Accept-Language} the fallback. See
 * {@link TimezoneAwareLocaleResolver}.
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    public static final String LANG_PARAM = "lang";

    @Bean
    public LocaleResolver localeResolver() {
        TimezoneAwareLocaleResolver resolver = new TimezoneAwareLocaleResolver();
        resolver.setCookieName("SYNCTOOL_LANG");
        resolver.setCookieMaxAge(60 * 60 * 24 * 365);
        resolver.setCookiePath("/");
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        // A stale or hand-edited cookie should degrade to the detected language, not 500 the
        // page — the resolver already narrows unsupported values to "absent".
        resolver.setRejectInvalidCookies(false);
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(LANG_PARAM);
        // Do not fail the request on a malformed lang value; keep the current locale instead.
        interceptor.setIgnoreInvalidLocale(true);
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }

    /** Exposes the message source to code that formats messages outside a request. */
    @Bean
    public MessageSourceAccessorHolder messageSourceAccessorHolder(MessageSource messageSource) {
        return new MessageSourceAccessorHolder(messageSource);
    }

    /** Small holder so services can resolve i18n keys without a static lookup. */
    public static class MessageSourceAccessorHolder {
        private final MessageSource messageSource;

        public MessageSourceAccessorHolder(MessageSource messageSource) {
            this.messageSource = messageSource;
        }

        public String get(String key, Locale locale, Object... args) {
            return messageSource.getMessage(key, args, key, locale);
        }
    }
}
