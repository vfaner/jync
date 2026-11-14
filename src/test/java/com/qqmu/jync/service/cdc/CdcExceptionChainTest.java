package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.SQLException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * The probes that classify a failed write walk the driver's exception chain.
 *
 * <p>{@link SQLException#setNextException} accepts any instance, including the receiver
 * itself, and the JDK does not reject a chain that points back at its head. An unbounded
 * walk over such a chain is a busy loop inside the catch block of a CDC write: the consumer
 * never returns, the position never advances, and nothing is logged. Every probe stops at a
 * repeated link.
 */
class CdcExceptionChainTest {

    private static final Duration BOUND = Duration.ofSeconds(2);

    @Test
    void aColumnProbeStopsAtASelfReferencingChain() {
        SQLException loop = new SQLException("boom");
        loop.setNextException(loop);

        Boolean missing = assertTimeoutPreemptively(BOUND,
                () -> CdcEventApplier.isMissingColumn(loop));

        assertThat(missing).isFalse();
    }

    @Test
    void aDdlObjectProbeStopsAtASelfReferencingChain() {
        SQLException loop = new SQLException("boom");
        loop.setNextException(loop);

        Boolean missing = assertTimeoutPreemptively(BOUND,
                () -> CdcDdlTaskService.isMissingObject(loop));

        assertThat(missing).isFalse();
    }
}
