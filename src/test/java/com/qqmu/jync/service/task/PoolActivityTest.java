package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;

/**
 * How many sync cycles may share one connection configuration. A cycle holds one connection
 * per side for its whole run, so the table counts seats, not tasks: a project whose source
 * and target are the same configuration takes both of its seats. The ceiling is half the
 * pool, which leaves the other half for the workers of the cycles that were admitted.
 */
class PoolActivityTest {

    private final SyncProperties properties = new SyncProperties();

    @Test
    void aCycleCountsOneSeatOnEachSide() {
        PoolActivity activity = new PoolActivity(properties);

        assertThat(activity.tryEnter(1L, 2L)).isTrue();

        assertThat(activity.activeOn(1L)).isEqualTo(1);
        assertThat(activity.activeOn(2L)).isEqualTo(1);
        assertThat(activity.activeOn(3L)).isZero();
    }

    @Test
    void aProjectOnOneConfigurationCountsBothOfItsSeats() {
        PoolActivity activity = new PoolActivity(properties);

        assertThat(activity.tryEnter(1L, 1L)).isTrue();

        // Pool of 5 allows 2 seats: this cycle used both, so a second one is refused.
        assertThat(activity.activeOn(1L)).isEqualTo(2);
        assertThat(activity.tryEnter(1L, 1L)).isFalse();
    }

    @Test
    void admissionStopsAtHalfThePoolAndLeavesTheCountsAlone() {
        PoolActivity activity = new PoolActivity(properties);

        assertThat(activity.tryEnter(1L, 2L)).isTrue();
        assertThat(activity.tryEnter(1L, 3L)).isTrue();

        // Sharing config 1 with two cycles is the ceiling (pool 5 -> 2 seats).
        assertThat(activity.tryEnter(1L, 4L)).isFalse();
        assertThat(activity.activeOn(1L)).isEqualTo(2);
        assertThat(activity.activeOn(4L)).isZero();
        assertThat(activity.tryEnter(4L, 5L)).isTrue();
    }

    @Test
    void leavingFreesTheSeat() {
        PoolActivity activity = new PoolActivity(properties);
        activity.tryEnter(1L, 2L);

        activity.leave(1L, 2L);

        assertThat(activity.activeOn(1L)).isZero();
        assertThat(activity.activeOn(2L)).isZero();
        assertThat(activity.tryEnter(1L, 3L)).isTrue();
    }

    @Test
    void anUnhandledLeavingDoesNotDriveTheCountNegative() {
        PoolActivity activity = new PoolActivity(properties);

        activity.leave(1L, 2L);

        assertThat(activity.activeOn(1L)).isZero();
    }

    @Test
    void anUnsavedProjectIsAdmittedWithoutBeingCounted() {
        PoolActivity activity = new PoolActivity(properties);

        assertThat(activity.tryEnter(null, null)).isTrue();

        activity.leave(null, null);
        assertThat(activity.activeOn(null)).isZero();
    }
}
