package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcBaselineAnchor;
import com.qqmu.jync.service.cdc.CdcBaselineService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The one-click cache rebuild: refuses a running project, optionally sweeps the project's key
 * prefix, re-anchors the stream, projects every table again, and records the audit row.
 */
class RedisRebuildServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long TARGET_ID = 9L;

    private ProjectRepository projects;
    private ProjectService projectService;
    private SyncContextFactory contextFactory;
    private RedisTargetService targets;
    private RedisBaselineService baseline;
    private CdcBaselineService cdcBaseline;
    private CdcPositionStore positionStore;
    private RedisPrefixSweeper sweeper;
    private AdminAuditService auditService;
    private SyncTaskStore taskStore;
    private RedisProjectionPolicy policy;

    private Project project;
    private RedisTarget target;
    private SyncContext ctx;
    private RedisRebuildService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        projectService = mock(ProjectService.class);
        contextFactory = mock(SyncContextFactory.class);
        targets = mock(RedisTargetService.class);
        baseline = mock(RedisBaselineService.class);
        cdcBaseline = mock(CdcBaselineService.class);
        positionStore = mock(CdcPositionStore.class);
        sweeper = mock(RedisPrefixSweeper.class);
        auditService = mock(AdminAuditService.class);
        taskStore = mock(SyncTaskStore.class);
        policy = mock(RedisProjectionPolicy.class);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("orders-cache");
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(TARGET_ID);
        project.setEnabled(false);

        SyncConfig config = new SyncConfig();
        config.setRedisProjection(RedisProjectionConfig.defaultFor(PROJECT_ID));
        ctx = SyncContext.builder().project(project).config(config).build();

        target = new RedisTarget();
        target.setId(TARGET_ID);
        target.setName("cache");
        target.setHost("127.0.0.1");
        target.setPort(6379);

        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(contextFactory.buildForRedis(eq(project), any(), any(), any())).thenReturn(ctx);
        when(baseline.run(eq(ctx), any(), any()))
                .thenReturn(new RedisBaselineService.BaselineResult(2, 7));
        when(targets.find(TARGET_ID)).thenReturn(Optional.of(target));

        service = new RedisRebuildService(projects, projectService, contextFactory, targets,
                baseline, cdcBaseline, positionStore, sweeper, auditService, taskStore, policy);
    }

    @Test
    void rebuildRequiresAStoppedProject() {
        project.setEnabled(true);
        when(projectService.isRunning(project)).thenReturn(true);

        assertThatThrownBy(() -> service.rebuild(PROJECT_ID, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.redis.rebuild.mustStop");

        verifyNoInteractions(sweeper, baseline, cdcBaseline, positionStore);
    }

    @Test
    void aRebuildRefusesAPrefixAnotherProjectAlreadyOwns() {
        // The sweep deletes every key under the project's current prefix. If the config arrived
        // by a path that never ran the save-time conflict check (a switch back to Redis, a
        // clone, a direct edit), that prefix may belong to another project — rebuilding would
        // delete or overwrite keys this project never wrote.
        Project owner = new Project();
        owner.setId(2L);
        when(policy.conflictingProject(project, "jync:1:")).thenReturn(owner);

        assertThatThrownBy(() -> service.rebuild(PROJECT_ID, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.redis.prefixConflict");

        verifyNoInteractions(sweeper, baseline);
        verify(positionStore, never()).delete(any());
        verify(positionStore, never()).resetAnchorKeepingProjectionSignature(any());
    }

    @Test
    void theAnchorGoesBeforeAnythingIsDestroyedOrRewritten() {
        service.rebuild(PROJECT_ID, true);

        // If the sweep or the baseline dies halfway, a surviving baselineDone=true would let
        // the consumer stream onto a half-written cache; resetting the anchor first makes the
        // next start rerun the whole baseline instead. The row itself stays: it also carries
        // the contract of the keys still in Redis, which a partial sweep has not cleared.
        InOrder order = inOrder(positionStore, sweeper, baseline);
        order.verify(positionStore).resetAnchorKeepingProjectionSignature(PROJECT_ID);
        order.verify(sweeper).sweep(target, "jync:1:");
        order.verify(baseline).run(eq(ctx), any(), any());
    }

    @Test
    void aFailedRebuildStillLeavesTheNextStartARebuildToDo() {
        when(baseline.run(eq(ctx), any(), any()))
                .thenThrow(new RedisProjectionException("redis down"));

        assertThatThrownBy(() -> service.rebuild(PROJECT_ID, true))
                .isInstanceOf(RedisProjectionException.class);

        verify(positionStore).resetAnchorKeepingProjectionSignature(PROJECT_ID);
    }

    @Test
    void rebuildRequiresARedisTargetKind() {
        project.setTargetKind(SyncTargetKind.JDBC);
        when(projectService.isRunning(project)).thenReturn(false);

        assertThatThrownBy(() -> service.rebuild(PROJECT_ID, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.redis.rebuild.notRedis");
    }

    @Test
    void missingProjectIsReportedWithTheSharedKey() {
        when(projects.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rebuild(42L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.not.found");
    }

    @Test
    void sweepOnlyMatchesTheProjectPrefix() {
        when(sweeper.sweep(target, "jync:1:")).thenReturn(4L);

        RedisRebuildService.RebuildResult result = service.rebuild(PROJECT_ID, true);

        verify(sweeper).sweep(target, "jync:1:");
        assertThat(result.tables()).isEqualTo(2);
        assertThat(result.rows()).isEqualTo(7);
        assertThat(result.sweptKeys()).isEqualTo(4);
    }

    @Test
    void aRebuildWithoutASweepIsRefusedWhenTheOldKeysHaveAnotherFormat() {
        // The keys were written as hashes and the project now projects JSON documents. A
        // baseline without the sweep re-writes every key it projects and answers WRONGTYPE on
        // the first one that already exists; there is no way to convert a key type in place,
        // so the sweep is the only correct rebuild of a changed contract.
        CdcPosition position = new CdcPosition();
        position.setProjectId(PROJECT_ID);
        position.setProjectionSignature("HASH|jync:1:");
        when(positionStore.find(PROJECT_ID)).thenReturn(Optional.of(position));

        assertThatThrownBy(() -> service.rebuild(PROJECT_ID, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.redis.rebuild.sweepRequired");

        verify(positionStore, never()).delete(PROJECT_ID);
        verifyNoInteractions(sweeper, baseline);
    }

    @Test
    void aSweepingRebuildAlsoDeletesThePrefixTheOldKeysWereWrittenUnder() {
        // A prefix change alone never hits WRONGTYPE, but the old namespace is orphaned: the
        // sweep only knows the CURRENT prefix, so without this the old keys stay in Redis
        // forever. The position carries the prefix the keys were actually built with.
        CdcPosition position = new CdcPosition();
        position.setProjectId(PROJECT_ID);
        position.setProjectionSignature("HASH|jync:old:");
        when(positionStore.find(PROJECT_ID)).thenReturn(Optional.of(position));
        when(sweeper.sweep(target, "jync:old:")).thenReturn(3L);
        when(sweeper.sweep(target, "jync:1:")).thenReturn(4L);

        RedisRebuildService.RebuildResult result = service.rebuild(PROJECT_ID, true);

        InOrder order = inOrder(sweeper);
        order.verify(sweeper).sweep(target, "jync:old:");
        order.verify(sweeper).sweep(target, "jync:1:");
        assertThat(result.sweptKeys()).isEqualTo(7L);
    }

    @Test
    void anOldPrefixAnotherProjectNowClaimsIsLeftAlone() {
        // The save-time conflict check only sees CURRENT prefixes: once this project moved to
        // jync:1:, its old jync:old: namespace was free and another project was allowed to
        // claim it. Sweeping the stale signature's prefix would delete that project's live
        // cache — the confirm dialog only promised to delete this project's keys.
        CdcPosition position = new CdcPosition();
        position.setProjectId(PROJECT_ID);
        position.setProjectionSignature("HASH|jync:old:");
        when(positionStore.find(PROJECT_ID)).thenReturn(Optional.of(position));
        Project sibling = new Project();
        sibling.setId(2L);
        when(policy.conflictingProject(project, "jync:old:")).thenReturn(sibling);
        when(sweeper.sweep(target, "jync:1:")).thenReturn(4L);

        RedisRebuildService.RebuildResult result = service.rebuild(PROJECT_ID, true);

        verify(sweeper, never()).sweep(target, "jync:old:");
        verify(sweeper).sweep(target, "jync:1:");
        assertThat(result.sweptKeys()).isEqualTo(4L);
    }

    @Test
    void aMatchingContractStillAllowsARebuildWithoutASweep() {
        CdcPosition position = new CdcPosition();
        position.setProjectId(PROJECT_ID);
        position.setProjectionSignature("JSON|jync:1:");
        when(positionStore.find(PROJECT_ID)).thenReturn(Optional.of(position));

        RedisRebuildService.RebuildResult result = service.rebuild(PROJECT_ID, false);

        verifyNoInteractions(sweeper);
        assertThat(result.tables()).isEqualTo(2);
    }

    @Test
    void noSweepMeansTheSweeperIsNeverTouched() {
        RedisRebuildService.RebuildResult result = service.rebuild(PROJECT_ID, false);

        verifyNoInteractions(sweeper, targets);
        assertThat(result.sweptKeys()).isZero();
    }

    @Test
    void rebuildRecordsAnAuditRow() {
        service.rebuild(PROJECT_ID, true);

        verify(auditService).record(eq(AuditAction.REDIS_REBUILD),
                contains("{audit.detail.redisRebuild}"));
    }

    @Test
    void rebuildReAnchorsTheStreamThroughTheJdbcAnchor() {
        service.rebuild(PROJECT_ID, false);

        ArgumentCaptor<CdcBaselineAnchor> anchor = ArgumentCaptor.forClass(CdcBaselineAnchor.class);
        verify(baseline).run(eq(ctx), any(), anchor.capture());
        anchor.getValue().record(ctx);
        // The anchor is the JDBC baseline's own, so a Redis rebuild records exactly the
        // position a normal baseline would (binlog file/pos, SCN, LSN, ... — not a Redis-only one).
        verify(cdcBaseline).recordBaselineAnchor(ctx);
    }

    @Test
    void aSuccessfulRebuildClearsAParkedErrorHat() {
        service.rebuild(PROJECT_ID, false);

        verify(taskStore).markStoppedClearingResult(PROJECT_ID);
    }

    @Test
    void aFailedRebuildLeavesTheTaskStateAlone() {
        when(baseline.run(eq(ctx), any(), any()))
                .thenThrow(new RedisProjectionException("redis down"));

        assertThatThrownBy(() -> service.rebuild(PROJECT_ID, false))
                .isInstanceOf(RedisProjectionException.class);

        verify(taskStore, never()).markStoppedClearingResult(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void aSweepWithoutAConfiguredTargetIsRefused() {
        when(targets.find(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rebuild(PROJECT_ID, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(TARGET_ID));
        verifyNoInteractions(baseline);
    }
}
