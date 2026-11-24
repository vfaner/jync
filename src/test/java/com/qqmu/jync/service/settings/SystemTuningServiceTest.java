package com.qqmu.jync.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.SystemSetting;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.SystemSettingRepository;
import com.qqmu.jync.service.auth.AccessGuard;

class SystemTuningServiceTest {

    private final SyncProperties properties = new SyncProperties();
    private final SystemSettingRepository repository = mock(SystemSettingRepository.class);
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final Map<String, String> rows = new LinkedHashMap<>();
    private SystemTuningService tuning;

    @BeforeEach
    void setUp() {
        when(repository.findAll()).thenAnswer(inv -> {
            List<SystemSetting> list = new ArrayList<>();
            rows.forEach((key, value) -> {
                SystemSetting row = new SystemSetting();
                row.setKey(key);
                row.setValue(value);
                row.setUpdatedAt(Instant.now());
                list.add(row);
            });
            return list;
        });
        when(settings.putValue(anyString(), anyString())).thenAnswer(inv -> {
            rows.put(inv.getArgument(0), inv.getArgument(1));
            return java.util.Optional.empty();
        });
        org.mockito.Mockito.doAnswer(inv -> {
            rows.remove(inv.getArgument(0, String.class));
            return null;
        }).when(settings).removeValue(anyString());
        tuning = new SystemTuningService(properties, repository, settings);
    }

    @Test
    void baseValuesComeFromApplicationYmlAndAreNotOverriddenByDefault() {
        assertThat(tuning.items()).hasSize(9)
                .anySatisfy(item -> {
                    assertThat(item.key()).isEqualTo("sync.batch-size");
                    assertThat(item.value()).isEqualTo("500");
                    assertThat(item.base()).isEqualTo("500");
                    assertThat(item.overridden()).isFalse();
                });
    }

    @Test
    void previewShowsOldNewAndRangeFailuresWithoutWriting() {
        List<SystemTuningService.Change> changes = tuning.preview(
                Map.of("sync.batch-size", "1000",
                        "sync.adaptive-chunk-size", "off"),
                List.of());
        assertThat(changes).anySatisfy(change -> {
            assertThat(change.key()).isEqualTo("sync.batch-size");
            assertThat(change.before()).isEqualTo("500");
            assertThat(change.after()).isEqualTo("1000");
            assertThat(change.beforeIsDefault()).isTrue();
        });
        assertThat(changes).anySatisfy(change -> {
            assertThat(change.key()).isEqualTo("sync.adaptive-chunk-size");
            assertThat(change.after()).isEqualTo("false");
        });

        assertThatThrownBy(() -> tuning.preview(Map.of("sync.chunk-size", "10"), List.of()))
                .hasMessage("error.system.param.range");
        assertThatThrownBy(() -> tuning.preview(Map.of("sync.retention-days", "1"), List.of()))
                .hasMessage("error.system.param.notAllowed");
        assertThatThrownBy(() -> tuning.preview(Map.of("sync.batch-size", "x"), List.of()))
                .hasMessage("error.system.param.invalid");
        assertThatThrownBy(() -> tuning.preview(Map.of("sync.batch-size", "1000"),
                List.of("sync.batch-size")))
                .hasMessage("error.system.param.conflict");
        verify(settings, never()).putValue(anyString(), anyString());
    }

    @Test
    void anAdminApplyPersistsAndUpdatesTheLivePropertiesBean() {
        tuning.apply(Map.of("sync.batch-size", "1000", "sync.max-retries", "1"),
                List.of(), admin());

        assertThat(properties.getBatchSize()).isEqualTo(1000);
        assertThat(properties.getMaxRetries()).isEqualTo(1);
        assertThat(rows).containsEntry("sync.batch-size", "1000")
                .containsEntry("sync.max-retries", "1");
        assertThat(tuning.items()).anySatisfy(item -> {
            assertThat(item.key()).isEqualTo("sync.batch-size");
            assertThat(item.overridden()).isTrue();
        });
    }

    @Test
    void resetRemovesTheOverrideAndRestoresTheYmlBase() {
        rows.put("sync.batch-size", "1000");
        tuning.refresh();
        assertThat(properties.getBatchSize()).isEqualTo(1000);

        tuning.apply(Map.of(), List.of("sync.batch-size"), admin());

        assertThat(properties.getBatchSize()).isEqualTo(500);
        assertThat(rows).doesNotContainKey("sync.batch-size");
        verify(settings).removeValue("sync.batch-size");
    }

    @Test
    void anOperatorCannotApplySystemParameters() {
        assertThatThrownBy(() -> tuning.apply(Map.of("sync.batch-size", "1000"), List.of(),
                AccessGuard.of("ops1", UserRole.OPS)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(properties.getBatchSize()).isEqualTo(500);
    }

    @Test
    void anInvalidStoredRowFallsBackToTheYmlBaseAndDoesNotBreakRefresh() {
        rows.put("sync.chunk-size", "not-a-number");
        tuning.refresh();

        assertThat(properties.getChunkSize()).isEqualTo(100_000);
        assertThat(tuning.items()).anySatisfy(item -> {
            assertThat(item.key()).isEqualTo("sync.chunk-size");
            assertThat(item.value()).isEqualTo("100000");
            assertThat(item.overridden()).isFalse();
        });
    }

    @Test
    void anotherNodesWriteConvergesOnRefresh() {
        rows.put("sync.full-load-fetch-size", "5000");

        tuning.refresh();

        assertThat(properties.getFullLoadFetchSize()).isEqualTo(5000);
    }

    private AccessGuard admin() {
        return AccessGuard.of("admin", UserRole.ADMIN);
    }
}
