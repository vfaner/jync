package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The summary badge stores what the run wrote, which for a program failure is an i18n key.
 * Rendering it raw leaks {@code error.etl.*} onto the page; the helper translates the known
 * key and leaves a driver's own message exactly as the database said it.
 */
class EtlResultTextTest {

    private static final Locale ZH = Locale.SIMPLIFIED_CHINESE;

    private final EtlResultText text = new EtlResultText(messages());

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        // Mirrors spring.messages.fallback-to-system-locale=false: without it a JVM whose
        // default locale is zh_CN answers Locale.ENGLISH from the Chinese bundle.
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void aProgramKeyIsTranslatedAndTheColumnAfterTheColonStaysVisible() {
        assertThat(text.display("FAILED side=PROGRAM error=error.etl.resumeKey.null:id", ZH))
                .isEqualTo("FAILED side=PROGRAM error=取数结果中的续传键值为 NULL，"
                        + "续跑永远读不到该行。 id");
    }

    @Test
    void aKeyWithoutAContextSuffixIsTranslated() {
        assertThat(text.display("FAILED error=error.etl.queue.full", ZH))
                .isEqualTo("FAILED error=" + message("error.etl.queue.full"));
    }

    @Test
    void aSuccessfulSummaryIsLeftAlone() {
        assertThat(text.display("SUCCESS read=10 written=10", ZH))
                .isEqualTo("SUCCESS read=10 written=10");
        assertThat(text.display("PARTIAL members=2 ok=1 failed=1 skipped=0 read=5 written=5", ZH))
                .isEqualTo("PARTIAL members=2 ok=1 failed=1 skipped=0 read=5 written=5");
    }

    @Test
    void aDriverMessageOnTheErrorTailIsLeftExactlyAsStored() {
        String driver = "FAILED side=TARGET error=Unknown column 'region' in 'field list'";
        assertThat(text.display(driver, ZH)).isEqualTo(driver);
    }

    @Test
    void aKeyShapedTailTheBundleDoesNotDefineIsNotHalfTranslated() {
        String unknown = "FAILED error=error.etl.someFutureKey:margin";
        assertThat(text.display(unknown, ZH)).isEqualTo(unknown);
    }

    @Test
    void englishUsesTheEnglishBundle() {
        assertThat(text.display("FAILED error=error.etl.resumeKey.null:id", Locale.ENGLISH))
                .isEqualTo("FAILED error=The resume key is NULL in the extraction result;"
                        + " a continuation can never reach that row. id");
    }

    @Test
    void aMissingSummaryStaysMissing() {
        assertThat(text.display(null, ZH)).isNull();
    }

    private String message(String key) {
        return messages().getMessage(key, null, ZH);
    }
}
