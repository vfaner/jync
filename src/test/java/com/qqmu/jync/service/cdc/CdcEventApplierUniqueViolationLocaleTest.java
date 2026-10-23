package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * CDC replays an INSERT as a plain INSERT and demotes it to an UPDATE only when this
 * candidate test says the row may already exist. The text fallback is what covers drivers
 * that report a null or generic SQLState.
 */
class CdcEventApplierUniqueViolationLocaleTest {

    @Test
    void englishDuplicateMessagesAreStillRecognisedUnderATurkishDefaultLocale() {
        // tr-TR 的默认小写把 "DUPLICATE" 折叠成 "duplıcate"，兜底失效：回放时插入
        // 已存在的行会被记成硬失败，而不是退化成 UPDATE。
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(CdcEventApplier.isUniqueViolation(
                    new SQLException("DUPLICATE entry '1' for key 'PRIMARY'", null, 0)))
                    .isTrue();
        } finally {
            Locale.setDefault(previous);
        }
    }
}
