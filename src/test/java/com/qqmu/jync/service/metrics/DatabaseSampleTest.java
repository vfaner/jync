package com.qqmu.jync.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.DbMetricSample;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.DbMetricSampleRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.DatabaseHealthService;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metrics.runtime.RuntimeMetricsReader;
import com.qqmu.jync.service.metrics.runtime.RuntimeMetricsRouter;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The connection sampler only watches connections a project actually uses, and never lets a
 * monitoring failure masquerade as a data problem — or the other way round.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseSampleTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Mock private ProjectRepository projectRepository;
    @Mock private DatabaseConfigRepository configRepository;
    @Mock private DatabaseHealthService healthService;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private MetadataReaderFactory readerFactory;
    @Mock private RuntimeMetricsRouter router;
    @Mock private DbMetricSampleRepository sampleRepository;

    private DatabaseSample sample;

    @BeforeEach
    void setUp() {
        sample = new DatabaseSample(projectRepository, configRepository, healthService,
                dataSourceManager, readerFactory, router, sampleRepository);
    }

    @Test
    void onlyConnectionsReferencedByAProjectAreSampled() throws Exception {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(
                project(1L, 10L, 20L), project(2L, 20L, 30L)));
        when(configRepository.findAllById(any())).thenReturn(List.of(
                config(10L), config(20L), config(30L)));
        when(healthService.check(anyList(), eq(false))).thenReturn(List.of(
                health(10L, true), health(20L, true), health(30L, true)));

        List<DbMetricSample> rows = sample.sample(NOW, false);

        assertThat(rows).extracting(DbMetricSample::getConnectionId)
                .containsExactlyInAnyOrder(10L, 20L, 30L);
        ArgumentCaptor<Iterable<Long>> requested = ArgumentCaptor.forClass(Iterable.class);
        verify(configRepository).findAllById(requested.capture());
        assertThat(requested.getValue()).containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    @Test
    void aConnectionNoEnabledProjectUsesIsNotSampled() throws Exception {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of());

        assertThat(sample.sample(NOW, false)).isEmpty();
        verify(sampleRepository, never()).save(any());
    }

    @Test
    void anUnhealthyConnectionStillGetsAFailureSample() throws Exception {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, 10L, null)));
        when(configRepository.findAllById(any())).thenReturn(List.of(config(10L)));
        when(healthService.check(anyList(), eq(false))).thenReturn(List.of(
                new DatabaseHealthService.Health(10L, false, 3000, "[SQLState 08S01] refused")));

        List<DbMetricSample> rows = sample.sample(NOW, false);

        assertThat(rows).hasSize(1);
        DbMetricSample row = rows.get(0);
        assertThat(row.isOk()).isFalse();
        assertThat(row.getLatencyMs()).isEqualTo(3000);
        assertThat(row.getMessage()).isEqualTo("[SQLState 08S01] refused");
        verify(router, never()).read(any(), any());
    }

    @Test
    void aRuntimeReadFailureDoesNotEraseTheHealthResult() throws Exception {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, 10L, null)));
        when(configRepository.findAllById(any())).thenReturn(List.of(config(10L)));
        when(healthService.check(anyList(), eq(false))).thenReturn(List.of(health(10L, true)));
        HikariDataSource pool = mock(HikariDataSource.class);
        when(dataSourceManager.createTransientDataSource(any())).thenReturn(pool);
        when(pool.getConnection()).thenAnswer(inv -> mock(Connection.class));
        when(router.read(any(), any())).thenThrow(new java.sql.SQLException("ORA-00942"));

        assertThatCode(() -> sample.sample(NOW, false)).doesNotThrowAnyException();

        DbMetricSample row = rows().get(0);
        assertThat(row.isOk()).isTrue();
        assertThat(row.getSessions()).isNull();
    }

    @Test
    void theSecondReadingTurnsTheCounterIntoAqps() throws Exception {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, 10L, null)));
        when(configRepository.findAllById(any())).thenReturn(List.of(config(10L)));
        when(healthService.check(anyList(), eq(false))).thenReturn(List.of(health(10L, true)));
        stubPool();
        when(router.read(any(), any()))
                .thenReturn(Optional.of(new RuntimeMetricsReader.Reading(
                        12, 1000L, 98.5, 2)))
                .thenReturn(Optional.of(new RuntimeMetricsReader.Reading(
                        13, 7000L, 98.6, 1)));

        sample.sample(NOW, false);
        List<DbMetricSample> second = sample.sample(NOW.plusSeconds(60), false);

        assertThat(rowsFrom(second).getQps()).isEqualTo(100.0);
        assertThat(first().getQps()).isNull();
    }

    @Test
    void aRestartedServerCounterDoesNotProduceANegativeQps() throws Exception {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, 10L, null)));
        when(configRepository.findAllById(any())).thenReturn(List.of(config(10L)));
        when(healthService.check(anyList(), eq(false))).thenReturn(List.of(health(10L, true)));
        stubPool();
        when(router.read(any(), any()))
                .thenReturn(Optional.of(new RuntimeMetricsReader.Reading(1, 5000L, null, null)))
                .thenReturn(Optional.of(new RuntimeMetricsReader.Reading(1, 10L, null, null)));

        sample.sample(NOW, false);
        sample.sample(NOW.plusSeconds(60), false);

        assertThat(rowsFrom(rows()).getQps()).isNull();
    }

    /**
     * A connection can leave the project set (deleted, or its project disabled) and come back
     * later. The counter mark from the first stint must not survive the gap: subtracting it
     * would turn a rate over ten minutes into a "current" qps, and the map would grow by one
     * entry for every connection ever sampled.
     */
    @Test
    void aCounterMarkIsForgottenWhenTheConnectionLeavesTheSampleSet() throws Exception {
        when(projectRepository.findByEnabledTrue())
                .thenReturn(List.of(project(1L, 10L, null)),
                        List.of(project(1L, 20L, null)),
                        List.of(project(1L, 10L, null)));
        when(configRepository.findAllById(any()))
                .thenReturn(List.of(config(10L)), List.of(config(20L)), List.of(config(10L)));
        when(healthService.check(anyList(), eq(false)))
                .thenReturn(List.of(health(10L, true)), List.of(health(20L, true)),
                        List.of(health(10L, true)));
        stubPool();
        when(router.read(any(), any()))
                .thenReturn(Optional.of(new RuntimeMetricsReader.Reading(1, 1000L, null, null)))
                .thenReturn(Optional.of(new RuntimeMetricsReader.Reading(1, 5000L, null, null)))
                .thenReturn(Optional.of(new RuntimeMetricsReader.Reading(1, 7000L, null, null)));

        sample.sample(NOW, false);
        sample.sample(NOW.plusSeconds(60), false);
        List<DbMetricSample> third = sample.sample(NOW.plusSeconds(120), false);

        assertThat(third.get(0).getQps()).isNull();
    }

    @Test
    void capacityIsOnlyReadWhenTheSlowerScheduleSaysSo() throws Exception {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, 10L, null)));
        when(configRepository.findAllById(any())).thenReturn(List.of(config(10L)));
        when(healthService.check(anyList(), eq(false))).thenReturn(List.of(health(10L, true)));
        stubPool();
        when(router.read(any(), any())).thenReturn(Optional.empty());
        MetadataReader reader = mock(MetadataReader.class);
        when(readerFactory.forType(any())).thenReturn(reader);
        when(reader.estimateTableStats(any(), any()))
                .thenReturn(Map.of("a", new MetadataReader.TableStat(10L, 1024L),
                        "b", new MetadataReader.TableStat(5L, 512L)));

        sample.sample(NOW, false);
        assertThat(first().getTableCount()).isNull();

        sample.sample(NOW, true);
        assertThat(rowsFrom(rows()).getTableCount()).isEqualTo(2);
        assertThat(rowsFrom(rows()).getRowCount()).isEqualTo(15);
        assertThat(rowsFrom(rows()).getBytes()).isEqualTo(1536);
    }

    private void stubPool() throws Exception {
        HikariDataSource pool = mock(HikariDataSource.class);
        when(dataSourceManager.createTransientDataSource(any())).thenReturn(pool);
        when(pool.getConnection()).thenAnswer(inv -> mock(Connection.class));
    }

    private List<DbMetricSample> rows() {
        ArgumentCaptor<DbMetricSample> captor = ArgumentCaptor.forClass(DbMetricSample.class);
        verify(sampleRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private DbMetricSample first() {
        return rows().get(0);
    }

    private DbMetricSample rowsFrom(List<DbMetricSample> rows) {
        return rows.get(rows.size() - 1);
    }

    private Project project(Long id, Long sourceId, Long targetId) {
        Project project = new Project();
        project.setId(id);
        project.setSourceDbId(sourceId);
        project.setTargetDbId(targetId);
        return project;
    }

    private DatabaseConfig config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName("db" + id);
        config.setType(DatabaseType.MYSQL);
        config.setRole(ConnectionRole.SOURCE);
        return config;
    }

    private DatabaseHealthService.Health health(Long id, boolean ok) {
        return new DatabaseHealthService.Health(id, ok, 5, ok ? null : "boom");
    }
}
