package com.qqmu.jync.service.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AlertState;
import com.qqmu.jync.model.AlertWebhookFormat;
import com.qqmu.jync.repository.AlertStateRepository;

/**
 * The alert state machine: one message when a fault starts, one per cooldown while it lasts,
 * one when it clears.
 *
 * <p>The state lives in the database, not in memory, because two nodes evaluate concurrently
 * and a failover must not restart the cooldown clock.
 */
@ExtendWith(MockitoExtension.class)
class AlertEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    @Mock private AlertConfigService configService;
    @Mock private AlertSignalCollector collector;
    @Mock private AlertNotifier notifier;
    @Mock private AlertStateRepository stateRepository;
    @Mock private MessageSource messages;

    private AlertConfig config;
    private AlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        config = new AlertConfig();
        config.setId(1L);
        config.setEnabled(true);
        config.setFormat(AlertWebhookFormat.WE_COM);
        config.setCooldownMinutes(30);
        config.setCategories("SYNC_FAILURE,POLL_STUCK,DATA_DRIFT");
        lenient().when(configService.load()).thenReturn(config);
        lenient().when(configService.webhookUrlOrNull()).thenReturn("https://hook.example/x");
        lenient().when(messages.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(stateRepository.findAll()).thenReturn(new ArrayList<>());
        evaluator = new AlertEvaluator(configService, collector, notifier, stateRepository,
                messages, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AlertEvent event(AlertCategory category) {
        return new AlertEvent(category, 1L, "proj", "EVENTS",
                "alert.detail.syncFailure", new Object[]{3}, NOW);
    }

    private static AlertState state(AlertCategory category, boolean active, Instant lastNotified) {
        AlertState state = new AlertState();
        state.setCategory(category);
        state.setProjectId(1L);
        state.setObjectKey("EVENTS");
        state.setSubject("proj");
        state.setActive(active);
        state.setLastNotifiedAt(lastNotified);
        return state;
    }

    private void sendWasCalled() {
        verify(notifier).send(eq("https://hook.example/x"), eq(AlertWebhookFormat.WE_COM),
                anyString());
    }

    @Test
    void aNewConditionIsSentOnceAndRecorded() {
        when(collector.collect()).thenReturn(List.of(event(AlertCategory.SYNC_FAILURE)));
        when(stateRepository.findByCategoryAndProjectIdAndObjectKey(
                AlertCategory.SYNC_FAILURE, 1L, "EVENTS")).thenReturn(Optional.empty());

        evaluator.evaluate();

        sendWasCalled();
        ArgumentCaptor<AlertState> saved = ArgumentCaptor.forClass(AlertState.class);
        verify(stateRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().getFirstSeenAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getLastNotifiedAt()).isEqualTo(NOW);
    }

    @Test
    void anActiveConditionInsideTheCooldownStaysQuiet() {
        AlertState active = state(AlertCategory.SYNC_FAILURE, true, NOW.minusSeconds(10 * 60));
        when(collector.collect()).thenReturn(List.of(event(AlertCategory.SYNC_FAILURE)));
        when(stateRepository.findAll()).thenReturn(List.of(active));

        evaluator.evaluate();

        verify(notifier, never()).send(anyString(), any(), anyString());
    }

    @Test
    void anActiveConditionIsRepeatedAfterTheCooldown() {
        AlertState active = state(AlertCategory.SYNC_FAILURE, true, NOW.minusSeconds(31 * 60));
        when(collector.collect()).thenReturn(List.of(event(AlertCategory.SYNC_FAILURE)));
        when(stateRepository.findAll()).thenReturn(List.of(active));

        evaluator.evaluate();

        sendWasCalled();
        ArgumentCaptor<AlertState> saved = ArgumentCaptor.forClass(AlertState.class);
        verify(stateRepository).save(saved.capture());
        assertThat(saved.getValue().getLastNotifiedAt()).isEqualTo(NOW);
    }

    @Test
    void aClearedConditionSendsARecoveryAndDeactivates() {
        AlertState active = state(AlertCategory.SYNC_FAILURE, true, NOW.minusSeconds(60));
        when(collector.collect()).thenReturn(List.of());
        when(stateRepository.findAll()).thenReturn(List.of(active));

        evaluator.evaluate();

        // "alert.webhook.recovery" is the only key the stub returns for the recovery text.
        verify(notifier).send(anyString(), any(), contains("alert.webhook.recovery"));
        ArgumentCaptor<AlertState> saved = ArgumentCaptor.forClass(AlertState.class);
        verify(stateRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();
    }

    @Test
    void aDeselectedCategoryClearsSilently() {
        AlertState active = state(AlertCategory.CDC_STOPPED, true, NOW.minusSeconds(60));
        when(collector.collect()).thenReturn(List.of());
        when(stateRepository.findAll()).thenReturn(List.of(active));

        evaluator.evaluate();

        verify(notifier, never()).send(anyString(), any(), anyString());
        ArgumentCaptor<AlertState> saved = ArgumentCaptor.forClass(AlertState.class);
        verify(stateRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();
    }

    @Test
    void nothingIsSentWhenAlertingIsOff() {
        config.setEnabled(false);

        evaluator.evaluate();

        verify(collector, never()).collect();
        verify(notifier, never()).send(anyString(), any(), anyString());
    }

    @Test
    void nothingIsSentWithoutAUrl() {
        when(configService.webhookUrlOrNull()).thenReturn(null);

        evaluator.evaluate();

        verify(collector, never()).collect();
        verify(notifier, never()).send(anyString(), any(), anyString());
    }

    @Test
    void aDriftEventIsAnnouncedOnlyForANewerRow() {
        AlertState active = state(AlertCategory.DATA_DRIFT, true, NOW.minusSeconds(60));
        active.setLastEventAt(NOW);
        when(collector.collect()).thenReturn(List.of(event(AlertCategory.DATA_DRIFT)));
        when(stateRepository.findAll()).thenReturn(List.of(active));

        evaluator.evaluate();

        verify(notifier, never()).send(anyString(), any(), anyString());
    }

    @Test
    void aDriftEventDisappearingIsNotARecovery() {
        AlertState active = state(AlertCategory.DATA_DRIFT, true, NOW.minusSeconds(60));
        active.setLastEventAt(NOW.minusSeconds(120));
        when(collector.collect()).thenReturn(List.of());
        when(stateRepository.findAll()).thenReturn(List.of(active));

        evaluator.evaluate();

        verify(notifier, never()).send(anyString(), any(), anyString());
        ArgumentCaptor<AlertState> saved = ArgumentCaptor.forClass(AlertState.class);
        verify(stateRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();
    }

    @Test
    void aFailingCollectorDoesNotBreakTheSchedule() {
        when(collector.collect()).thenThrow(new IllegalStateException("source unreachable"));

        evaluator.evaluate();

        verify(notifier, never()).send(anyString(), any(), anyString());
    }

    @Test
    void theMessageNamesTheCategoryTheSubjectAndTheObject() {
        when(collector.collect()).thenReturn(List.of(event(AlertCategory.SYNC_FAILURE)));
        when(stateRepository.findByCategoryAndProjectIdAndObjectKey(
                AlertCategory.SYNC_FAILURE, 1L, "EVENTS")).thenReturn(Optional.empty());
        when(messages.getMessage(eq("alert.webhook.message"), any(), any(Locale.class)))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArgument(1);
                    return "MSG " + args[0] + "|" + args[1] + "|" + args[2] + "|" + args[3];
                });

        evaluator.evaluate();

        verify(notifier).send(anyString(), any(),
                eq("MSG alert.category.syncFailure|proj|EVENTS|alert.detail.syncFailure"));
    }

    @Test
    void anEmptyObjectIsShownAsADash() {
        AlertEvent projectLevel = new AlertEvent(AlertCategory.POLL_STUCK, 1L, "proj", "",
                "alert.detail.pollStuck", new Object[]{35, 30}, NOW);
        when(collector.collect()).thenReturn(List.of(projectLevel));
        when(stateRepository.findByCategoryAndProjectIdAndObjectKey(
                AlertCategory.POLL_STUCK, 1L, "")).thenReturn(Optional.empty());
        when(messages.getMessage(eq("alert.webhook.message"), any(), any(Locale.class)))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArgument(1);
                    return "MSG " + args[2];
                });

        evaluator.evaluate();

        verify(notifier).send(anyString(), any(), eq("MSG -"));
    }

    @Test
    void theClockIsTheOnlyTimeSource() {
        // Guards the cooldown arithmetic: the state's timestamps compare against the injected
        // clock, not the wall clock, which is what makes the test deterministic.
        AlertState active = state(AlertCategory.SYNC_FAILURE, true, NOW.minusSeconds(29 * 60));
        when(collector.collect()).thenReturn(List.of(event(AlertCategory.SYNC_FAILURE)));
        when(stateRepository.findAll()).thenReturn(List.of(active));

        evaluator.evaluate();

        verify(notifier, never()).send(anyString(), any(), anyString());
    }
}
