package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.cdc.CdcTargetRepairService;
import com.qqmu.jync.service.cdc.CdcTargetTableMissingException;

/**
 * A fan-out target gets the same missing-table repair as the single-target consumers: repair
 * the table, then replay the buffered transaction on top of the reloaded snapshot.
 */
class RepairingEventApplierTest {

    private final EventApplier delegate = mock(EventApplier.class);
    private final CdcTargetRepairService repair = mock(CdcTargetRepairService.class);
    private final com.qqmu.jync.service.sync.SyncContext ctx =
            mock(com.qqmu.jync.service.sync.SyncContext.class);

    private static CdcTargetRepairService.Outcome outcome(
            CdcTargetRepairService.Status status) {
        return new CdcTargetRepairService.Outcome(status, List.of("orders"), "detail");
    }

    @Test
    void aRepairedTargetReplaysTheTransactionOnce() throws Exception {
        when(ctx.projectId()).thenReturn(1L);
        CdcTargetTableMissingException fault = new CdcTargetTableMissingException(
                "orders", "orders", "fanout_t2", new SQLException("no table"));
        org.mockito.Mockito.doThrow(fault).doNothing()
                .when(delegate).applyTransaction(any(), any(), any());
        when(repair.repair(ctx, fault)).thenReturn(
                outcome(CdcTargetRepairService.Status.REPAIRED));
        RepairingEventApplier applier = new RepairingEventApplier(delegate, repair, ctx);

        applier.applyTransaction("tx", "lsn:1", List.of());

        verify(delegate, times(2)).applyTransaction("tx", "lsn:1", List.of());
        verify(repair).repair(ctx, fault);
    }

    @Test
    void anUnrepairableFailureIsRethrown() throws Exception {
        when(ctx.projectId()).thenReturn(1L);
        SQLException fault = new SQLException("column missing");
        org.mockito.Mockito.doThrow(fault).when(delegate)
                .applyTransaction(any(), any(), any());
        when(repair.repair(ctx, fault)).thenReturn(
                outcome(CdcTargetRepairService.Status.NOT_APPLICABLE));
        RepairingEventApplier applier = new RepairingEventApplier(delegate, repair, ctx);

        assertThatThrownBy(() -> applier.applyTransaction("tx", "lsn:1", List.of()))
                .isSameAs(fault);
        verify(delegate, times(1)).applyTransaction(any(), any(), any());
    }

    @Test
    void withoutARepairServiceTheFailureIsUnchanged() throws Exception {
        SQLException fault = new SQLException("boom");
        org.mockito.Mockito.doThrow(fault).when(delegate)
                .applyTransaction(any(), any(), any());
        RepairingEventApplier applier = new RepairingEventApplier(delegate, null, ctx);

        assertThatThrownBy(() -> applier.applyTransaction("tx", "lsn:1", List.of()))
                .isSameAs(fault);
    }
}
