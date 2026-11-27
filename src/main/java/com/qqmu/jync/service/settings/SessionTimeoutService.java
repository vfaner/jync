package com.qqmu.jync.service.settings;

import java.time.Duration;
import java.util.Optional;

import jakarta.servlet.ServletContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.ServletContextAware;
import org.springframework.stereotype.Service;

import com.qqmu.jync.service.auth.AccessGuard;

import lombok.extern.slf4j.Slf4j;

/**
 * The idle timeout for authenticated HTTP sessions.
 *
 * <p>{@code server.servlet.session.timeout} is the startup base. An administrator can override
 * it through the AI assistant's confirmation card; the value is stored in {@code system_setting}
 * so it survives a restart and every node converges within about 30 seconds. New sessions get
 * the new timeout from the servlet context, and {@link SessionTimeoutFilter} applies it to an
 * existing session on its next request.
 *
 * <p>The lower bound is 30 minutes: making an operations console expire faster than that is
 * hostile, not a security gain. The upper bound is 30 days.
 */
@Service
@Slf4j
public class SessionTimeoutService implements ServletContextAware {

    public static final String KEY = "session.timeout";
    public static final int MIN_MINUTES = 30;
    public static final int MAX_MINUTES = 43_200;

    private final SystemSettingService settings;
    private volatile int baseMinutes;
    private volatile int effectiveMinutes;
    private volatile ServletContext servletContext;

    @Autowired
    public SessionTimeoutService(SystemSettingService settings,
                                 @Value("${server.servlet.session.timeout:8h}")
                                 Duration configured) {
        this(settings, (int) configured.toMinutes());
    }

    /** Test seam: a base timeout without a Spring context. */
    SessionTimeoutService(SystemSettingService settings, int baseMinutes) {
        this.settings = settings;
        this.baseMinutes = clamp(baseMinutes);
        this.effectiveMinutes = this.baseMinutes;
    }

    @Override
    public void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
        refresh();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    /**
     * Applies rows written by another node or a configuration import. The local save calls
     * {@link #apply} directly and does not wait for this tick.
     */
    @Scheduled(initialDelay = 30_000L, fixedDelay = 30_000L)
    public synchronized void refresh() {
        int next = baseMinutes;
        try {
            Optional<String> raw = settings.findValue(KEY);
            if (raw.isPresent()) {
                try {
                    next = clamp(Integer.parseInt(raw.get().trim()));
                } catch (NumberFormatException | ArithmeticException e) {
                    log.warn("Ignoring invalid session timeout override '{}'", raw.get());
                }
            }
        } catch (RuntimeException e) {
            // A missing/unreachable settings row leaves the YAML/context base in force.
            log.debug("Could not read the session timeout override: {}", e.toString());
        }
        if (next != effectiveMinutes) {
            effectiveMinutes = next;
            applyToContext();
        }
    }

    /** The effective timeout in minutes, for the session filter and the settings card. */
    public int currentMinutes() {
        return effectiveMinutes;
    }

    /** A card preview; validation happens again when the card is confirmed. */
    public synchronized Change preview(int minutes) {
        validate(minutes);
        refresh();
        int before = effectiveMinutes;
        if (minutes == before) {
            throw new IllegalArgumentException("error.session.timeout.unchanged");
        }
        return new Change(before, minutes, !hasOverride());
    }

    /** Persists the override and applies it to new and (on next request) active sessions. */
    public synchronized void apply(int minutes, AccessGuard actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new AccessDeniedException(AccessGuard.DENIED_KEY);
        }
        validate(minutes);
        settings.putValue(KEY, Integer.toString(minutes));
        effectiveMinutes = minutes;
        applyToContext();
    }

    private boolean hasOverride() {
        try {
            return settings.findValue(KEY).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void applyToContext() {
        ServletContext context = servletContext;
        if (context == null) {
            return;
        }
        try {
            context.setSessionTimeout(effectiveMinutes);
        } catch (RuntimeException e) {
            // The filter still applies the value to active sessions; a restart repairs the
            // servlet-context default for sessions created before the next request.
            log.warn("Could not apply session timeout {}m to the servlet context: {}",
                    effectiveMinutes, e.toString());
        }
    }

    private static void validate(int minutes) {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            throw new IllegalArgumentException("error.session.timeout.range");
        }
    }

    private static int clamp(int minutes) {
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
    }

    /** Before/after values shown on the confirmation card. */
    public record Change(int beforeMinutes, int afterMinutes, boolean beforeIsDefault) {
    }
}
