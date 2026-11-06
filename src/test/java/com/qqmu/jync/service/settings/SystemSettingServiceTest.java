package com.qqmu.jync.service.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.SystemSetting;
import com.qqmu.jync.repository.SystemSettingRepository;

/**
 * The key/value store behind page-editable settings.
 *
 * <p>Values are stored as text rather than columns: the table must accept a new setting
 * without a schema change, and {@code ddl-auto: update} is the only migration mechanism in
 * this application.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "metrics.enabled=false",
        "app.update-check.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:systemsetting;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class SystemSettingServiceTest {

    private static final SystemSettingKey KEY = SystemSettingKey.SECURITY_REQUIRE_HTTPS;

    @Autowired
    private SystemSettingService service;

    @Autowired
    private SystemSettingRepository repository;

    @Test
    void storesAValueAndReadsItBack() {
        service.putBoolean(KEY, true);

        assertThat(service.find(KEY)).contains("true");
        assertThat(service.getBoolean(KEY, false)).isTrue();
    }

    @Test
    void overwritingAKeyKeepsASingleRowAndUpdatesTheTimestamp() throws Exception {
        service.putBoolean(KEY, true);
        Instant first = repository.findById(KEY.key()).orElseThrow().getUpdatedAt();

        Thread.sleep(5);
        service.putBoolean(KEY, false);

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(KEY.key()).orElseThrow().getUpdatedAt()).isAfter(first);
        assertThat(service.getBoolean(KEY, true)).isFalse();
    }

    @Test
    void aMissingKeyReadsAsAbsentAndFallsBack() {
        assertThat(service.find(KEY)).isEmpty();
        assertThat(service.findBoolean(KEY)).isEmpty();
        assertThat(service.getBoolean(KEY, true)).isTrue();
    }

    @Test
    void anUnparseableStoredValueFallsBackInsteadOfFailingTheRequest() {
        SystemSetting row = new SystemSetting();
        row.setKey(KEY.key());
        row.setValue("maybe");
        row.setUpdatedAt(Instant.now());
        repository.saveAndFlush(row);

        assertThat(service.findBoolean(KEY)).isEmpty();
        assertThat(service.getBoolean(KEY, false)).isFalse();
    }

    @Test
    void putReturnsThePreviousValue() {
        assertThat(service.putBoolean(KEY, true)).isEmpty();
        assertThat(service.putBoolean(KEY, false)).contains(true);
    }

    @Test
    void everyWriteBumpsTheRevisionSoCachesCanInvalidateImmediately() {
        long before = service.revision();

        service.putBoolean(KEY, true);

        assertThat(service.revision()).isGreaterThan(before);
    }
}
