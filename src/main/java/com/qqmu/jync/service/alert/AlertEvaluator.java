package com.qqmu.jync.service.alert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AlertState;
import com.qqmu.jync.repository.AlertStateRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Compares the collector's current faults with {@code alert_state} and decides what to send.
 *
 * <p>A condition that starts is announced once, repeated after the configured cooldown while it
 * lasts, and answered with a recovery message when it clears. An event category (data drift) is
 * announced once per distinct event and has no recovery.
 *
 * <p>States live in the database, but a shared row is not enough to stop two nodes from both
 * sending: both read the same values before either writes. Every announcement therefore goes
 * through a conditional update on the row (see {@link AlertStateRepository}), and a node that
 * loses that update stays quiet. The insert path relies on the unique key for the same reason.
 *
 * <p>Every failure is contained: the scheduled run must survive a collector exception, an
 * undecryptable URL or a single unsaveable row.
 */
@Service
@Slf4j
public class AlertEvaluator {

    private final AlertConfigService configService;
    private final AlertSignalCollector collector;
    private final AlertNotifier notifier;
    private final AlertStateRepository stateRepository;
    private final MessageSource messages;
    private final Clock clock;

    /** Spring wires the wall clock; the test seam pins it. */
    @Autowired
    public AlertEvaluator(AlertConfigService configService,
                          AlertSignalCollector collector,
                          AlertNotifier notifier,
                          AlertStateRepository stateRepository,
                          MessageSource messages) {
        this(configService, collector, notifier, stateRepository, messages, Clock.systemUTC());
    }

    AlertEvaluator(AlertConfigService configService,
                   AlertSignalCollector collector,
                   AlertNotifier notifier,
                   AlertStateRepository stateRepository,
                   MessageSource messages,
                   Clock clock) {
        this.configService = configService;
        this.collector = collector;
        this.notifier = notifier;
        this.stateRepository = stateRepository;
        this.messages = messages;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sync.alert.evaluate-interval-ms:60000}",
            initialDelayString = "${sync.alert.initial-delay-ms:45000}")
    public void evaluate() {
        AlertConfig config;
        String url;
        try {
            config = configService.load();
            if (!config.isEnabled()) {
                return;
            }
            url = configService.webhookUrlOrNull();
        } catch (RuntimeException e) {
            log.warn("Alert settings could not be read: {}", e.toString());
            return;
        }
        if (url == null) {
            return;
        }

        List<AlertEvent> events;
        try {
            // Scheduler threads carry no request locale: the configured alert language decides
            // how the message text reads, including the health verdicts collected here.
            events = collector.collect(config.alertLocale());
        } catch (RuntimeException e) {
            // One broken signal source must not silence every other alert this minute.
            log.warn("Alert signal collection failed: {}", e.toString());
            return;
        }

        Instant now = clock.instant();
        Set<AlertCategory> enabled = config.enabledCategorySet();
        Map<String, AlertEvent> current = new LinkedHashMap<>();
        for (AlertEvent event : events) {
            if (event != null && enabled.contains(event.category())) {
                current.put(event.dedupKey(), event);
            }
        }

        Map<String, AlertState> states = new LinkedHashMap<>();
        for (AlertState state : stateRepository.findAll()) {
            states.put(key(state), state);
        }

        for (AlertEvent event : current.values()) {
            try {
                AlertState state = states.get(event.dedupKey());
                if (state == null) {
                    state = stateRepository.findByCategoryAndProjectIdAndObjectKey(
                                    event.category(), event.projectId(), event.object())
                            .orElse(null);
                }
                if (state == null) {
                    announceNew(event, config, url, now);
                } else {
                    handleActive(event, state, config, url, now);
                }
            } catch (RuntimeException e) {
                log.warn("Alert state for {} could not be updated: {}",
                        event.dedupKey(), e.toString());
            }
        }

        for (AlertState state : states.values()) {
            if (!state.isActive() || current.containsKey(key(state))) {
                continue;
            }
            try {
                clearance(state, enabled, url, config, now);
            } catch (RuntimeException e) {
                log.warn("Alert state {} could not be cleared: {}", key(state), e.toString());
            }
        }

        try {
            stateRepository.deleteInactive();
        } catch (RuntimeException e) {
            // Housekeeping only: the next run tries again, and no alert depends on it.
            log.debug("Cleared alert states could not be pruned: {}", e.toString());
        }
    }

    /**
     * The first announcement of a fault. The insert is the claim: the unique key lets exactly
     * one node through, and the loser's save fails — it must not send.
     */
    private void announceNew(AlertEvent event, AlertConfig config, String url, Instant now) {
        AlertState state = new AlertState();
        state.setCategory(event.category());
        state.setProjectId(event.projectId());
        state.setObjectKey(event.object());
        state.setSubject(event.subject());
        state.setActive(true);
        state.setFirstSeenAt(now);
        state.setLastNotifiedAt(now);
        if (event.category().isEvent()) {
            state.setLastEventAt(event.occurredAt());
        }
        if (!save(state)) {
            return;
        }
        notifier.send(url, config.formatOrDefault(), message(event, config.alertLocale()));
    }

    private void handleActive(AlertEvent event, AlertState state, AlertConfig config,
                              String url, Instant now) {
        Locale locale = config.alertLocale();
        if (!state.isActive()) {
            boolean eventCategory = event.category().isEvent();
            Instant lastEventAt = eventCategory ? event.occurredAt() : null;
            if (stateRepository.claimStart(state.getId(), now, event.subject(), now,
                    lastEventAt) == 0) {
                return;
            }
            state.setActive(true);
            state.setFirstSeenAt(now);
            state.setSubject(event.subject());
            state.setLastNotifiedAt(now);
            state.setLastEventAt(lastEventAt);
        } else if (event.category().isEvent()) {
            // An event is news, not a condition: only a row newer than the one already
            // announced is worth another message, and only one node may announce it.
            Instant last = state.getLastEventAt();
            if (last != null && !event.occurredAt().isAfter(last)) {
                return;
            }
            if (stateRepository.claimEvent(state.getId(), now, event.occurredAt()) == 0) {
                return;
            }
            state.setLastNotifiedAt(now);
            state.setLastEventAt(event.occurredAt());
        } else {
            long cooldownMs = Duration.ofMinutes(config.cooldownMinutesOrDefault()).toMillis();
            Instant last = state.getLastNotifiedAt();
            if (last != null && now.toEpochMilli() - last.toEpochMilli() < cooldownMs) {
                return;
            }
            if (stateRepository.claimRepeat(state.getId(), now, now.minusMillis(cooldownMs)) == 0) {
                return;
            }
            state.setLastNotifiedAt(now);
        }
        notifier.send(url, config.formatOrDefault(), message(event, locale));
    }

    private void clearance(AlertState state, Set<AlertCategory> enabled, String url,
                           AlertConfig config, Instant now) {
        if (stateRepository.claimClearance(state.getId()) == 0) {
            // Another node cleared it in the same minute and sends the recovery, if any.
            return;
        }
        state.setActive(false);
        if (!enabled.contains(state.getCategory()) || state.getCategory().isEvent()) {
            return;
        }
        Locale locale = config.alertLocale();
        notifier.send(url, config.formatOrDefault(),
                messages.getMessage("alert.webhook.recovery",
                        new Object[]{categoryLabel(state.getCategory(), locale),
                                state.getSubject() == null ? "-" : state.getSubject(),
                                display(state.getObjectKey())},
                        locale));
    }

    private String message(AlertEvent event, Locale locale) {
        String detail = render(event.detailKey(), event.detailArgs(), locale);
        return messages.getMessage("alert.webhook.message",
                new Object[]{categoryLabel(event.category(), locale), event.subject(),
                        display(event.object()), detail},
                locale);
    }

    private String categoryLabel(AlertCategory category, Locale locale) {
        return render(category.messageKey(), new Object[0], locale);
    }

    private String render(String key, Object[] args, Locale locale) {
        try {
            return messages.getMessage(key, args, locale);
        } catch (RuntimeException e) {
            // A missing translation must not cost the alert itself.
            return key;
        }
    }

    private static String display(String object) {
        return object == null || object.isEmpty() ? "-" : object;
    }

    private static String key(AlertState state) {
        return state.getCategory().name() + ":" + state.getProjectId() + ":"
                + state.getObjectKey();
    }

    /**
     * @return true when this node inserted the row, false when another node got there first
     *         (the unique key refused the insert) or storage failed — in both cases the caller
     *         must not send, because it does not own the announcement
     */
    private boolean save(AlertState state) {
        try {
            stateRepository.save(state);
            return true;
        } catch (RuntimeException e) {
            log.debug("Alert state could not be saved: {}", e.toString());
            return false;
        }
    }
}
