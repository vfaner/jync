package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.task.SyncLockService;

/**
 * The project page has to tell "this round is still working" from "this round is wedged".
 * Both look identical from the outside — a next-run time that has already passed — but only
 * the second needs the operator to restart the instance.
 */
class ProjectCycleViewTest {

    private static final Instant START = Instant.parse("2026-10-26T02:33:20Z");

    /** The cycle's last progress; {@code now} is this plus the test's idle duration. */
    private static final Instant PROGRESS = START.plus(Duration.ofMinutes(5));

    private static ProjectService.CycleView view(long idleMinutes, long thresholdMs) {
        Instant now = PROGRESS.plus(Duration.ofMinutes(idleMinutes));
        SyncLockService.ActiveCycle cycle =
                new SyncLockService.ActiveCycle(1L, START, PROGRESS);
        return ProjectService.cycleView(cycle, thresholdMs, now);
    }

    @Test
    void aFreshCycleIsRunningAndNotStuck() {
        ProjectService.CycleView view = view(0, 30 * 60_000L);

        assertThat(view.running()).isTrue();
        assertThat(view.stuck()).isFalse();
        assertThat(view.minutesSinceProgress()).isZero();
        assertThat(view.stuckThresholdMinutes()).isEqualTo(30);
    }

    @Test
    void aCyclePastTheThresholdIsStuck() {
        ProjectService.CycleView view = view(45, 30 * 60_000L);

        assertThat(view.running()).isTrue();
        assertThat(view.stuck()).isTrue();
        assertThat(view.minutesSinceProgress()).isEqualTo(45);
    }

    @Test
    void theThresholdIsOffWhenSetToZero() {
        // A negative/zero setting disables detection rather than marking every cycle stuck.
        ProjectService.CycleView view = view(600, 0L);

        assertThat(view.running()).isTrue();
        assertThat(view.stuck()).isFalse();
    }

    @Test
    void timestampsAreRenderedInTheServerZone() {
        ProjectService.CycleView view = view(5, 30 * 60_000L);

        assertThat(view.startedAt()).isEqualTo(
                LocalDateTime.ofInstant(START, ZoneId.systemDefault()));
        assertThat(view.lastProgressAt()).isEqualTo(LocalDateTime.ofInstant(
                START.plus(Duration.ofMinutes(5)), ZoneId.systemDefault()));
    }
}
