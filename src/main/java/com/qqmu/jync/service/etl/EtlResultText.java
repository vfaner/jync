package com.qqmu.jync.service.etl;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Renders an ETL task's stored {@code lastRunResult} in the page's language.
 *
 * <p>The summary is composed when the run ends. A failure records whatever the run threw:
 * a driver's message, or — for a program failure — one of our own {@code error.etl.*} keys,
 * optionally with the offending column appended after a colon
 * ({@code error.etl.resumeKey.null:id}). The list badge and the last-run card printed it raw,
 * so a Chinese page showed the key. Translating only the known key keeps a driver's message
 * exactly as the database said it, and leaves an unrecognized key-shaped tail alone rather
 * than half-translating it.
 */
@Component("etlResultText")
public class EtlResultText {

    /** Returned by {@link MessageSource#getMessage} for a key a bundle does not define. */
    private static final String MISSING = "\u0000missing\u0000";

    /** Where the composers ({@code record}, {@code recordCrash}) put the run's message. */
    private static final String ERROR_MARK = " error=";

    private final MessageSource messageSource;

    public EtlResultText(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** Display form for the request's language; {@code null} stays {@code null}. */
    public String display(String raw) {
        return display(raw, LocaleContextHolder.getLocale());
    }

    String display(String raw, Locale locale) {
        if (raw == null) {
            return null;
        }
        int at = raw.indexOf(ERROR_MARK);
        if (at < 0) {
            return raw;
        }
        int start = at + ERROR_MARK.length();
        String error = raw.substring(start);
        int colon = error.indexOf(':');
        String key = colon < 0 ? error : error.substring(0, colon);
        String suffix = colon < 0 ? "" : error.substring(colon + 1);
        String text = messageSource.getMessage(key, null, MISSING, locale);
        if (MISSING.equals(text)) {
            return raw;
        }
        return raw.substring(0, start) + (suffix.isEmpty() ? text : text + " " + suffix);
    }
}
