package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.SyncTargetKind;

class ProjectTuningTest {

    private SyncConfig polling() {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);
        return config;
    }

    @Test
    void pollingProjectsAcceptBatchParallelismAndIntervalButNotTheCdcIdleField() {
        SyncConfig config = polling();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("batchSize", 2000L);
        changes.put("fullLoadParallelism", 4L);
        changes.put("pollIntervalMs", 5000L);

        ProjectTuning.apply(config, ProjectTuning.validate(config, changes));

        assertThat(config.getBatchSize()).isEqualTo(2000);
        assertThat(config.getFullLoadParallelism()).isEqualTo(4);
        assertThat(config.getPollIntervalMs()).isEqualTo(5000L);

        Map<String, Object> invalid = Map.of("cdcIdlePollMs", 500L);
        assertThatThrownBy(() -> ProjectTuning.validate(config, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.support.param.strategy");
    }

    @Test
    void aLogCdcProjectRejectsThePollIntervalAndOnlyPollingEnginesTakeTheIdleInterval() {
        SyncConfig binlog = polling();
        binlog.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        assertThatThrownBy(() -> ProjectTuning.validate(binlog, Map.of("pollIntervalMs", 2000L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.support.param.strategy");
        // binlog is push-based; the idle interval is a dead field there.
        assertThatThrownBy(() -> ProjectTuning.validate(binlog, Map.of("cdcIdlePollMs", 500L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.support.param.strategy");

        SyncConfig logminer = polling();
        logminer.setCaptureStrategy(CaptureStrategy.LOGMINER_CDC);
        List<ProjectTuning.Change> accepted =
                ProjectTuning.validate(logminer, Map.of("cdcIdlePollMs", 250L));
        assertThat(accepted).extracting(ProjectTuning.Change::after).containsExactly(250L);
    }

    @Test
    void boundsAreStrictSoTheAnswerMatchesWhatWouldBeSaved() {
        SyncConfig config = polling();
        assertThatThrownBy(() -> ProjectTuning.validate(config, Map.of("batchSize", 0L)))
                .hasMessage("error.support.param.range");
        assertThatThrownBy(() -> ProjectTuning.validate(config,
                Map.of("fullLoadParallelism", ProjectTuning.MAX_PARALLELISM + 1L)))
                .hasMessage("error.support.param.range");
        assertThatThrownBy(() -> ProjectTuning.validate(config, Map.of("pollIntervalMs", 199L)))
                .hasMessage("error.support.param.range");
    }

    @Test
    void unknownAndNonIntegralFieldsAreRefusedInsteadOfSilentlyIgnored() {
        SyncConfig config = polling();
        assertThatThrownBy(() -> ProjectTuning.validate(config, Map.of("syncDeletes", true)))
                .hasMessage("error.support.param.notAllowed");
        assertThatThrownBy(() -> ProjectTuning.validate(config, Map.of("batchSize", "2000")))
                .hasMessage("error.support.param.invalid");
        assertThatThrownBy(() -> ProjectTuning.validate(config, Map.of("batchSize", 1.5)))
                .hasMessage("error.support.param.invalid");
        assertThatThrownBy(() -> ProjectTuning.validate(config, Map.of()))
                .hasMessage("error.support.param.empty");
    }

    @Test
    void cursorAndScheduleRulesFollowTheStrategyAndTarget() {
        SyncConfig polling = polling();
        polling.setTables(new java.util.LinkedHashSet<>(java.util.List.of("ORDERS")));
        ProjectTuning.requireCursorApplicable(polling, SyncTargetKind.JDBC);
        ProjectTuning.requireScheduleApplicable(polling, SyncTargetKind.JDBC);

        SyncConfig binlog = polling();
        binlog.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        assertThatThrownBy(() -> ProjectTuning.requireCursorApplicable(
                binlog, SyncTargetKind.JDBC))
                .hasMessage("error.project.cursor.not.polling");
        assertThatThrownBy(() -> ProjectTuning.requireScheduleApplicable(
                binlog, SyncTargetKind.JDBC))
                .hasMessage("error.project.schedule.not.polling");
        assertThatThrownBy(() -> ProjectTuning.requireCursorApplicable(
                polling(), SyncTargetKind.REDIS))
                .hasMessage("error.project.cursor.not.polling");
        assertThatThrownBy(() -> ProjectTuning.requireScheduleApplicable(
                polling(), SyncTargetKind.REDIS))
                .hasMessage("error.project.schedule.not.polling");
    }

    @Test
    void aRedisTargetRejectsThePollIntervalEvenWithAPollingStrategy() {
        SyncConfig config = polling();
        assertThatThrownBy(() -> ProjectTuning.validate(config, SyncTargetKind.REDIS,
                Map.of("pollIntervalMs", 2000L)))
                .hasMessage("error.support.param.target");
        // Batch size and parallelism still drive the baseline load into Redis.
        ProjectTuning.apply(config, ProjectTuning.validate(config, SyncTargetKind.REDIS,
                Map.of("batchSize", 1000L, "fullLoadParallelism", 2L)));
        assertThat(config.getBatchSize()).isEqualTo(1000);
    }

    @Test
    void cursorColumnsMustNameASelectedTableAndAllowBlankToClear() {
        SyncConfig config = polling();
        config.setTables(new java.util.LinkedHashSet<>(java.util.List.of("ORDERS")));

        assertThat(ProjectTuning.normalizeColumns(config, Map.of("orders", " UPDATED_AT ")))
                .containsEntry("ORDERS", "UPDATED_AT");
        assertThat(ProjectTuning.normalizeColumns(config, Map.of("ORDERS", "")))
                .containsEntry("ORDERS", "");

        assertThatThrownBy(() -> ProjectTuning.normalizeColumns(config, Map.of("OTHER", "ID")))
                .hasMessage("error.project.cursor.table.notSelected");
        assertThatThrownBy(() -> ProjectTuning.normalizeColumns(config, Map.of(" ", "ID")))
                .hasMessage("error.project.cursor.table.invalid");
    }

    @Test
    void priorityIsDedupedCaseInsensitivelyAndCapped() {
        assertThat(ProjectTuning.normalizePriority(
                java.util.List.of("update_time", "UPDATE_TIME", "id", " ", "created_at")))
                .containsExactly("update_time", "id", "created_at");
        java.util.List<String> tooMany = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> "c" + i).toList();
        assertThatThrownBy(() -> ProjectTuning.normalizePriority(tooMany))
                .hasMessage("error.project.cursor.priority.too.many");
    }

    @Test
    void cronIsValidatedAndBlankMeansIntervalMode() {
        assertThat(ProjectTuning.normalizeCron("  ")).isNull();
        assertThat(ProjectTuning.normalizeCron(null)).isNull();
        assertThat(ProjectTuning.normalizeCron("0 0/5 * * * ?"))
                .isEqualTo("0 0/5 * * * ?");
        assertThatThrownBy(() -> ProjectTuning.normalizeCron("not a cron"))
                .hasMessage("error.project.cron.invalid");
    }

    @Test
    void theViewDistinguishesTheGlobalDefaultFromAConfiguredValue() {
        SyncConfig config = polling();
        Map<String, Object> before = ProjectTuning.view(config);
        assertThat(before).containsEntry("batchSize", null)
                .containsEntry("fullLoadParallelism", null)
                .containsEntry("pollIntervalMs", null)
                .containsEntry("cdcIdlePollMs", null);

        config.setBatchSize(1000);
        config.setPollIntervalMs(3000L);
        Map<String, Object> after = ProjectTuning.view(config);
        assertThat(after).containsEntry("batchSize", 1000L)
                .containsEntry("pollIntervalMs", 3000L);
        // A zero is stored by old rows but means "not configured" to the engine.
        config.setBatchSize(0);
        assertThat(ProjectTuning.view(config)).containsEntry("batchSize", null);
    }
}
