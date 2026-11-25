package com.qqmu.jync.service.fanout;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

/**
 * Per-target positions arrive as typed tokens from the non-binlog engines; each token must
 * land in the column that engine's single-target path writes, or the status panel reports
 * the wrong kind of progress (and a restart would resume from nothing).
 */
class FanoutRuntimePositionTokenTest {

    private final FanoutPositionStore store = mock(FanoutPositionStore.class);

    @Test
    void aBinlogPositionStaysFileAndOffset() {
        FanoutRuntime.recordTargetPosition(store, 1L, 9L, "mysql-bin.000007:1024");

        verify(store).advance(1L, 9L, "mysql-bin.000007", 1024L, null, null, null);
    }

    @Test
    void anLsnTokenGoesToTheLsnColumn() {
        FanoutRuntime.recordTargetPosition(store, 1L, 9L, "lsn:0/16B3748");

        verify(store).advance(1L, 9L, null, 0L, null, "0/16B3748", null);
    }

    @Test
    void anScnTokenGoesToTheScnColumn() {
        FanoutRuntime.recordTargetPosition(store, 1L, 9L, "scn:12345678");

        verify(store).advance(1L, 9L, null, 0L, null, null, 12345678L);
    }

    @Test
    void aYStreamPackedTokenUsesTheGenericTextColumn() {
        FanoutRuntime.recordTargetPosition(store, 1L, 9L, "ystream:scn=42;seq=7");

        verify(store).advance(1L, 9L, null, 0L, null, "scn=42;seq=7", null);
    }

    @Test
    void theBaselineBarrierTokenIsNotAPosition() {
        FanoutRuntime.recordTargetPosition(store, 1L, 9L, "baseline-done:abc");

        verifyNoInteractions(store);
    }

    @Test
    void aMalformedScnDoesNotFailTheCommitPath() {
        FanoutRuntime.recordTargetPosition(store, 1L, 9L, "scn:not-a-number");

        verifyNoInteractions(store);
    }
}
