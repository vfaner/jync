package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The change log's "via ... strategy" phrase is parsed by the details localizer, which matches
 * ASCII words. A default locale whose lowercase rules are not ASCII — Turkish turns "I" into a
 * dotless "ı" — changed the word the parser looked for.
 */
class SyncEngineStrategyLabelTest {

    private final Locale previous = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(previous);
    }

    @Test
    void theStrategyNameDoesNotFollowTheMachinesLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        assertThat(SyncEngine.strategyLabel(
                CursorStrategy.identity("id", Types.INTEGER, "primary key")))
                .isEqualTo("identity");
        assertThat(SyncEngine.strategyLabel(
                CursorStrategy.timestamp("updated_at", Types.TIMESTAMP, "updated column")))
                .isEqualTo("timestamp");
    }
}
