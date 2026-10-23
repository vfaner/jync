package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;

/**
 * Some drivers leave SQLState null or generic, so the unique-violation candidate test falls
 * back to the driver's English message. That fallback decides whether a rejected INSERT is
 * retried as an UPDATE or reported as a hard failure.
 */
class DataSyncServiceUniqueViolationLocaleTest {

    @Test
    void englishDuplicateMessagesAreStillRecognisedUnderATurkishDefaultLocale() {
        // tr-TR 的默认小写把 "DUPLICATE" 折叠成 "duplıcate"，与关键字比对失败：
        // 本来可以退化成 UPDATE 的行会被当成硬失败。
        DataSyncService service = new DataSyncService(new SyncProperties());

        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(service.isUniqueViolation(
                    new SQLException("DUPLICATE entry '1' for key 'PRIMARY'", null, 0)))
                    .isTrue();
        } finally {
            Locale.setDefault(previous);
        }
    }
}
