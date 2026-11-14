package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertState;

/**
 * The conditional updates that decide which node gets to send an alert.
 *
 * <p>Each of these is a single {@code UPDATE ... WHERE}: the database serializes the two nodes'
 * statements, so exactly one caller sees a count of 1. Losing the count means "someone else
 * announced it" and the caller stays quiet.
 */
@DataJpaTest
class AlertStateRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    @Autowired
    private AlertStateRepository repository;

    private AlertState row(boolean active, Instant lastNotified) {
        return row(active, lastNotified, "EVENTS");
    }

    private AlertState row(boolean active, Instant lastNotified, String objectKey) {
        AlertState state = new AlertState();
        state.setCategory(AlertCategory.SYNC_FAILURE);
        state.setProjectId(1L);
        state.setObjectKey(objectKey);
        state.setSubject("proj");
        state.setActive(active);
        state.setLastNotifiedAt(lastNotified);
        return repository.save(state);
    }

    @Test
    void theStartClaimIsWonExactlyOnce() {
        AlertState state = row(false, null);

        assertThat(repository.claimStart(state.getId(), NOW, "proj", NOW, null)).isEqualTo(1);
        assertThat(repository.claimStart(state.getId(), NOW.plusSeconds(1), "proj",
                NOW.plusSeconds(1), null)).isZero();

        AlertState reloaded = repository.findById(state.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getFirstSeenAt()).isEqualTo(NOW);
        assertThat(reloaded.getLastNotifiedAt()).isEqualTo(NOW);
    }

    @Test
    void theRepeatClaimHonoursTheCooldownWindow() {
        AlertState state = row(true, NOW.minusSeconds(31 * 60));

        assertThat(repository.claimRepeat(state.getId(), NOW, NOW.minusSeconds(30 * 60)))
                .isEqualTo(1);
        assertThat(repository.claimRepeat(state.getId(), NOW, NOW.minusSeconds(30 * 60)))
                .isZero();
        assertThat(repository.findById(state.getId()).orElseThrow().getLastNotifiedAt())
                .isEqualTo(NOW);
    }

    @Test
    void anEventOlderThanTheAnnouncedOneIsNotClaimed() {
        AlertState state = row(true, NOW.minusSeconds(60));
        state.setLastEventAt(NOW);
        repository.save(state);

        assertThat(repository.claimEvent(state.getId(), NOW, NOW.minusSeconds(5))).isZero();
        assertThat(repository.claimEvent(state.getId(), NOW, NOW.plusSeconds(5))).isEqualTo(1);
    }

    @Test
    void theClearanceClaimIsWonExactlyOnce() {
        AlertState state = row(true, NOW);

        assertThat(repository.claimClearance(state.getId())).isEqualTo(1);
        assertThat(repository.claimClearance(state.getId())).isZero();
    }

    @Test
    void inactiveRowsArePrunedAndActiveOnesKept() {
        row(false, NOW, "EVENTS");
        AlertState active = row(true, NOW, "TABLE");

        assertThat(repository.deleteInactive()).isEqualTo(1);

        assertThat(repository.findAll()).singleElement()
                .satisfies(remaining -> assertThat(remaining.getId()).isEqualTo(active.getId()));
    }
}
