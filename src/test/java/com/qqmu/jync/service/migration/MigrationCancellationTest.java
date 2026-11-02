package com.qqmu.jync.service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class MigrationCancellationTest {

    @Test
    void aFreshTokenIsNotCancelled() {
        MigrationCancellation cancellation = new MigrationCancellation();

        assertThat(cancellation.isCancelled()).isFalse();
        assertThatCode(cancellation::check).doesNotThrowAnyException();
    }

    @Test
    void cancelMakesTheNextCheckpointStop() {
        MigrationCancellation cancellation = new MigrationCancellation();
        cancellation.cancel();

        assertThat(cancellation.isCancelled()).isTrue();
        assertThatThrownBy(cancellation::check)
                .isInstanceOf(MigrationCancelledException.class);
    }

    @Test
    void aPastDeadlineCountsAsCancelled() {
        MigrationCancellation cancellation = new MigrationCancellation();
        cancellation.setDeadline(Instant.now().minusSeconds(1));

        assertThat(cancellation.isCancelled()).isTrue();
        assertThatThrownBy(cancellation::check)
                .isInstanceOf(MigrationCancelledException.class);
    }

    @Test
    void aFutureDeadlineDoesNotCancel() {
        MigrationCancellation cancellation = new MigrationCancellation();
        cancellation.setDeadline(Instant.now().plusSeconds(60));

        assertThat(cancellation.isCancelled()).isFalse();
    }

    @Test
    void precheckPassesUnlessItContainsAnError() {
        assertThat(new MigrationPrecheckResult(List.of(
                new MigrationPrecheckResult.Check(
                        MigrationPrecheckResult.Level.OK, "ok", "fine"),
                new MigrationPrecheckResult.Check(
                        MigrationPrecheckResult.Level.WARN, "warn", "careful")))
                .passed()).isTrue();

        assertThat(new MigrationPrecheckResult(List.of(
                new MigrationPrecheckResult.Check(
                        MigrationPrecheckResult.Level.ERROR, "bad", "cannot")))
                .passed()).isFalse();
    }
}
