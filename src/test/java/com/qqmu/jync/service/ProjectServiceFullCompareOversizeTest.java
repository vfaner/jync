package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The project-detail load warning for a FULL_COMPARE project.
 *
 * <p>Two things matter: the table is over {@code sync.full-compare-max-rows} (the explicit
 * choice bypasses that cap), or the whole-table read and upsert is too heavy for the task's
 * current poll interval. Both are advisory; neither changes scheduling.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceFullCompareOversizeTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private DatabaseConfigRepository databaseConfigRepository;
    @Mock private SyncProgressRepository progressRepository;
    @Mock private ChangeLogRepository changeLogRepository;
    @Mock private ChunkCheckpointRepository checkpointRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private MetadataReaderFactory readerFactory;
    @Mock private MetadataSnapshotService snapshotService;
    @Mock private SyncContextFactory contextFactory;
    @Mock private SyncScheduler scheduler;
    @Mock private SyncTaskRunner taskRunner;
    @Mock private SyncTaskStore taskStore;
    @Mock private SyncEngine syncEngine;
    @Mock private SyncLockService lockService;
    @Mock private AdminAuditService auditService;
    @Mock private com.qqmu.jync.service.cdc.CdcConsumerManager cdcManager;
    @Mock private SyncStateWriter stateWriter;
    @Mock private com.qqmu.jync.service.cdc.CdcPositionStore positionStore;

    private final SyncProperties properties = new SyncProperties();
    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, databaseConfigRepository,
                progressRepository, changeLogRepository, checkpointRepository, dataSourceManager,
                readerFactory, snapshotService, contextFactory, scheduler, taskRunner, taskStore,
                syncEngine, lockService, auditService, cdcManager, stateWriter, positionStore,
                properties);
    }

    private void snapshots(TableMeta... tables) {
        Map<String, TableMeta> byName = new LinkedHashMap<>();
        for (TableMeta table : tables) {
            byName.put(table.getName().toUpperCase(), table);
        }
        when(snapshotService.loadAll(1L, ObjectType.TABLE, TableMeta.class))
                .thenReturn(byName);
    }

    private static TableMeta table(String name, Long approximateRows, boolean withPrimaryKey) {
        TableMeta table = new TableMeta();
        table.setName(name);
        table.setApproximateRowCount(approximateRows);
        table.setPrimaryKeys(withPrimaryKey ? List.of("ID") : List.of());
        return table;
    }

    private static SyncConfig fullCompare(Set<String> tables, Long intervalMs) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.FULL_COMPARE);
        config.setTables(tables);
        config.setPollIntervalMs(intervalMs);
        // An empty selection that was never saved means "all tables"; an explicitly saved
        // selection means exactly what it names.
        config.setObjectSelectionExplicit(!tables.isEmpty());
        return config;
    }

    private List<ProjectService.FullCompareLoad> loads(SyncConfig config) {
        return service.inspectFullCompareLoad(1L, config).tables();
    }

    @Test
    void tablesOverTheConfiguredLimitAreReportedLargestFirst() {
        snapshots(
                table("SMALL", 100L, true),
                table("BIG", 5_000_000L, true),
                table("MEDIUM", 50_000L, true));

        ProjectService.FullCompareLoadReport report =
                service.inspectFullCompareLoad(1L, fullCompare(Set.of(), null));

        assertThat(report.maxRows()).isEqualTo(20_000L);
        assertThat(report.intervalMs()).isEqualTo(2_000L);
        assertThat(report.isOverCap()).isTrue();
        assertThat(report.tables()).extracting(load -> load.table().getName())
                .containsExactly("BIG", "MEDIUM");
        assertThat(report.tables()).extracting(ProjectService.FullCompareLoad::overCap)
                .containsExactly(true, true);
    }

    @Test
    void aTableAtTheLimitIsOnlyReportedWhenTheIntervalMakesItHeavy() {
        snapshots(table("AT_LIMIT", 20_000L, true));

        // 20 000 rows at the 2 s default is ~20 s of work: heavy, though not over the cap.
        ProjectService.FullCompareLoadReport defaultInterval =
                service.inspectFullCompareLoad(1L, fullCompare(Set.of(), null));
        assertThat(defaultInterval.tables()).hasSize(1);
        assertThat(defaultInterval.tables().get(0).overCap()).isFalse();

        // The same table at a minute is comfortable and should not be reported at all.
        ProjectService.FullCompareLoadReport slow =
                service.inspectFullCompareLoad(1L, fullCompare(Set.of(), 60_000L));
        assertThat(slow.tables()).isEmpty();
    }

    @Test
    void aTableBelowTheCapButHeavyForTheIntervalIsReported() {
        snapshots(table("FIFTEEN_K", 15_000L, true));

        ProjectService.FullCompareLoadReport report =
                service.inspectFullCompareLoad(1L, fullCompare(Set.of(), null));

        assertThat(report.tables()).hasSize(1);
        ProjectService.FullCompareLoad load = report.tables().get(0);
        assertThat(load.table().getName()).isEqualTo("FIFTEEN_K");
        assertThat(load.overCap()).isFalse();
        assertThat(load.recommendedIntervalMs()).isEqualTo(30_000L);
    }

    @Test
    void aTaskLevelIntervalOverridesTheGlobalDefault() {
        snapshots(table("FIFTEEN_K", 15_000L, true));

        assertThat(loads(fullCompare(Set.of(), 60_000L))).isEmpty();
        assertThat(loads(fullCompare(Set.of(), 2_000L))).hasSize(1);
    }

    @Test
    void unselectedTablesAreIgnored() {
        snapshots(
                table("CHOSEN", 100_000L, true),
                table("NOT_CHOSEN", 900_000L, true));

        assertThat(loads(fullCompare(Set.of("CHOSEN"), null)))
                .extracting(load -> load.table().getName())
                .containsExactly("CHOSEN");
    }

    @Test
    void aKeylessTableIsNotReported() {
        // FULL_COMPARE on a keyless table is a one-time seed: later cycles skip it, so it
        // does not re-read the source every interval and must not appear in this warning.
        snapshots(table("NO_KEY", 5_000_000L, false));

        assertThat(loads(fullCompare(Set.of(), null))).isEmpty();
    }

    @Test
    void anUnknownRowCountIsNotReported() {
        snapshots(table("UNKNOWN", null, true));

        assertThat(loads(fullCompare(Set.of(), null))).isEmpty();
    }

    @Test
    void aPollingProjectIsNeverReportedAndTheSnapshotIsNotRead() {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);

        ProjectService.FullCompareLoadReport report =
                service.inspectFullCompareLoad(1L, config);

        assertThat(report.tables()).isEmpty();
        verifyNoInteractions(snapshotService);
    }

    @Test
    void theKeysMatchHowTheSnapshotServiceStoresNames() {
        // loadAll() keys by upper-cased name; the report must still carry the source spelling
        // so the page names the table the operator selected.
        snapshots(table("MixedCase", 100_000L, true));

        assertThat(loads(fullCompare(Set.of(), null)))
                .extracting(load -> load.table().getName())
                .containsExactly("MixedCase");
    }

    @Test
    void aNullConfigFallsBackToTheGlobalIntervalAndReportsNothing() {
        ProjectService.FullCompareLoadReport report = service.inspectFullCompareLoad(1L, null);

        assertThat(report.intervalMs()).isEqualTo(2_000L);
        assertThat(report.tables()).isEmpty();
        verifyNoInteractions(snapshotService);
    }

    @Test
    void theReportCarriesTheEffectiveIntervalForThePageCopy() {
        when(snapshotService.loadAll(eq(1L), eq(ObjectType.TABLE), any()))
                .thenReturn(Map.of());

        ProjectService.FullCompareLoadReport report =
                service.inspectFullCompareLoad(1L, fullCompare(Set.of(), 15_000L));

        assertThat(report.intervalMs()).isEqualTo(15_000L);
    }
}
