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
import org.springframework.context.i18n.LocaleContextHolder;
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
 * announced once per distinct event and has no recovery. States live in the database so two
 * nodes and a failover share one clock.
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
            events = collector.collect();
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
                            .orElseGet(() -> newState(event));
                }
                handleActive(event, state, config, url, now);
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
    }

    private AlertState newState(AlertEvent event) {
        AlertState state = new AlertState();
        state.setCategory(event.category());
        state.setProjectId(event.projectId());
        state.setObjectKey(event.object());
        state.setSubject(event.subject());
        return state;
    }

    private void handleActive(AlertEvent event, AlertState state, AlertConfig config,
                              String url, Instant now) {
        String text;
        if (!state.isActive()) {
            state.setActive(true);
            state.setFirstSeenAt(now);
            state.setSubject(event.subject());
            text = message(event);
            state.setLastNotifiedAt(now);
            state.setLastEventAt(event.occurredAt());
        } else if (event.category().isEvent()) {
            // An event is news, not a condition: only a row newer than the one already
            // announced is worth another message.
            Instant last = state.getLastEventAt();
            if (last != null && !event.occurredAt().isAfter(last)) {
                return;
            }
            text = message(event);
            state.setLastNotifiedAt(now);
            state.setLastEventAt(event.occurredAt());
        } else {
            long cooldownMs = Duration.ofMinutes(config.cooldownMinutesOrDefault()).toMillis();
            Instant last = state.getLastNotifiedAt();
            if (last != null && now.toEpochMilli() - last.toEpochMilli() < cooldownMs) {
                return;
            }
            text = message(event);
            state.setLastNotifiedAt(now);
        }
        save(state);
        notifier.send(url, config.formatOrDefault(), text);
    }

    private void clearance(AlertState state, Set<AlertCategory> enabled, String url,
                           AlertConfig config, Instant now) {
        state.setActive(false);
        save(state);
        if (!enabled.contains(state.getCategory()) || state.getCategory().isEvent()) {
            return;
        }
        notifier.send(url, config.formatOrDefault(),
                messages.getMessage("alert.webhook.recovery",
                        new Object[]{categoryLabel(state.getCategory()),
                                state.getSubject() == null ? "-" : state.getSubject(),
                                display(state.getObjectKey())},
                        locale()));
    }

    private String message(AlertEvent event) {
        String detail = render(event.detailKey(), event.detailArgs());
        return messages.getMessage("alert.webhook.message",
                new Object[]{categoryLabel(event.category()), event.subject(),
                        display(event.object()), detail},
                locale());
    }

    private String categoryLabel(AlertCategory category) {
        return render(category.messageKey(), new Object[0]);
    }

    private String render(String key, Object[] args) {
        try {
            return messages.getMessage(key, args, locale());
        } catch (RuntimeException e) {
            // A missing translation must not cost the alert itself.
            return key;
        }
    }

    private static Locale locale() {
        return LocaleContextHolder.getLocale();
    }

    private static String display(String object) {
        return object == null || object.isEmpty() ? "-" : object;
    }

    private static String key(AlertState state) {
        return state.getCategory().name() + ":" + state.getProjectId() + ":"
                + state.getObjectKey();
    }

    private void save(AlertState state) {
        try {
            stateRepository.save(state);
        } catch (RuntimeException e) {
            // A cross-node insert race lands here; the other node's row wins and the next
            // evaluation reads it.
            log.debug("Alert state could not be saved: {}", e.toString());
        }
    }
}
