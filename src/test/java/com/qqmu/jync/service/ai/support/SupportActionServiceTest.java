package com.qqmu.jync.service.ai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.cdc.CdcNotHolderException;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcRestartFailedException;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * The confirmation endpoint is the second half of the two-phase design: the token prepared by
 * a tool is executed only after the human clicks, and the service methods re-apply the
 * ownership rule at execution time.
 */
class SupportActionServiceTest {

    private final SupportActionStore store = new SupportActionStore();
    private final ProjectService projects = mock(ProjectService.class);
    private final EtlTaskService etlTasks = mock(EtlTaskService.class);
    private final CdcOperationsService cdcOperations = mock(CdcOperationsService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AccessGuard ops = AccessGuard.of("ops1", UserRole.OPS);

    private SupportActionService service() {
        return new SupportActionService(store, projects, etlTasks, cdcOperations, audit);
    }

    private String token(String username, SupportActionStore.Kind kind, long id,
                         SupportActionStore.Action action, String name) {
        return store.put(username, kind, id, action, name);
    }

    @Test
    void aConfirmedProjectStopRunsTheServiceAndIsAudited() {
        String token = token("ops1", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.STOP, "订单同步");

        var result = service().execute(token, ops);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.OK);
        assertThat(result.messageKey()).isEqualTo("support.action.done");
        verify(projects).stop(7L, ops);
        verify(audit).record(eq(AuditAction.AI_SUPPORT_ACTION),
                contains("support.action.project.stop"), eq("ops1"));
    }

    @Test
    void aTokenPreparedForAnotherAccountIsRejectedWithoutExecuting() {
        String token = token("alice", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.STOP, "订单同步");

        var result = service().execute(token, ops);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.EXPIRED);
        verifyNoInteractions(projects);
    }

    /**
     * A card may outlive the ownership it was prepared under: an operator could be reassigned
     * or the row re-owned between the question and the click. The service check is the
     * authoritative one and its refusal must not be swallowed.
     */
    @Test
    void anOwnershipChangeBetweenCardAndClickIsRefusedAtExecution() {
        String token = token("ops1", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.START, "订单同步");
        doThrow(new AccessDeniedException("error.accessDenied"))
                .when(projects).start(7L, ops);

        var result = service().execute(token, ops);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.DENIED);
        assertThat(result.messageKey()).isEqualTo("error.accessDenied");
        verify(audit).record(eq(AuditAction.AI_SUPPORT_ACTION),
                contains("audit.support.outcome.failed"), eq("ops1"));
    }

    @Test
    void aStateRefusalKeepsItsOwnKey() {
        String token = token("ops1", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.SYNC_NOW, "订单同步");
        when(projects.syncNow(7L, ops))
                .thenThrow(new IllegalStateException("error.project.cdc.use.rebaseline"));

        var result = service().execute(token, ops);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.FAILED);
        assertThat(result.messageKey()).isEqualTo("error.project.cdc.use.rebaseline");
    }

    @Test
    void anEtlRunThatIsAlreadyRunningSaysSo() {
        String token = token("ops1", SupportActionStore.Kind.ETL_TASK, 3L,
                SupportActionStore.Action.RUN_NOW, "日汇总");
        when(etlTasks.submit(3L)).thenReturn(false);

        var result = service().execute(token, ops);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.FAILED);
        assertThat(result.messageKey()).isEqualTo("error.etl.running");
        verify(etlTasks).requireEditable(3L, ops);
    }

    @Test
    void aViewerCannotExecuteEvenWithAValidToken() {
        AccessGuard viewer = AccessGuard.of("view1", UserRole.VIEWER);
        String token = token("view1", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.START, "订单同步");

        var result = service().execute(token, viewer);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.DENIED);
        verifyNoInteractions(projects);
    }

    @Test
    void anUnknownTokenIsReportedAsExpired() {
        var result = service().execute("nope", ops);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.EXPIRED);
        verifyNoInteractions(projects, etlTasks, audit);
    }

    @Test
    void aConfirmedRebaselineRunsTheCdcOperationAndIsAudited() {
        String token = token("ops1", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.REBASELINE, "订单同步");
        when(cdcOperations.rebaseline(7L)).thenReturn(true);

        var result = service().execute(token, ops);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.OK);
        verify(cdcOperations).rebaseline(7L);
        verify(audit).record(eq(AuditAction.AI_SUPPORT_ACTION),
                contains("support.action.project.rebaseline"), eq("ops1"));
    }

    /**
     * A rebaseline on a node that does not hold the consumer lease is refused before any
     * mutation. The refusal is a conflict the operator must act on, so its message survives
     * instead of collapsing into the generic failure sentence.
     */
    @Test
    void aRebaselineRefusedByAnotherNodesLeaseKeepsItsKey() {
        String token = token("ops1", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.REBASELINE, "订单同步");
        when(cdcOperations.rebaseline(7L))
                .thenThrow(new CdcNotHolderException("cdc.rebaseline.not.holder"));

        var result = service().execute(token, ops);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.FAILED);
        assertThat(result.messageKey()).isEqualTo("cdc.rebaseline.not.holder");
    }

    /** The reset committed; the restart did not. The operator must be told, not shown a 500. */
    @Test
    void aRebaselineWhoseRestartFailedKeepsItsFixedKey() {
        String token = token("ops1", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.REBASELINE, "订单同步");
        when(cdcOperations.rebaseline(7L))
                .thenThrow(new CdcRestartFailedException(new RuntimeException("refused")));

        var result = service().execute(token, ops);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.FAILED);
        assertThat(result.messageKey()).isEqualTo(CdcRestartFailedException.MESSAGE_KEY);
    }

    @Test
    void anUnexpectedFailureIsReportedWithoutLeakingInternals() {
        String token = token("ops1", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.START, "订单同步");
        doThrow(new IllegalStateException("ORA-00942: table or view does not exist"))
                .when(projects).start(7L, ops);

        var result = service().execute(token, ops);

        assertThat(result.outcome()).isEqualTo(SupportActionService.Outcome.FAILED);
        assertThat(result.messageKey()).isEqualTo("support.action.failed");
        assertThat(result.messageKey()).doesNotContain("ORA");
        verify(audit).record(eq(AuditAction.AI_SUPPORT_ACTION), any(), eq("ops1"));
    }
}
