package com.qqmu.jync.service.settings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


/**
 * Decides whether Jync asks GitHub/Gitee for newer releases.
 *
 * <p>Priority, highest first:
 *
 * <ol>
 *   <li>an explicit configuration value ({@code APP_UPDATE_CHECK_ENABLED} or
 *       {@code app.update-check.enabled}) — always wins, and a typo fails the boot;</li>
 *   <li>the value saved from the 系统配置 page;</li>
 *   <li>{@code true} — a fresh installation checks unless someone says otherwise.</li>
 * </ol>
 *
 * <p>Unlike forced HTTPS there is nothing to protect and no way to lock oneself out, so the
 * page may switch this either way at any time. The database lookup is cached for a few seconds
 * because the layout asks on every page render; a write on this node bumps the service
 * revision and takes effect on the next request, while other nodes converge when their TTL
 * lapses.
 */
@Component
public class UpdateCheckResolver {

    public enum Source { CONFIG, DATABASE, DEFAULT }

    public record Effective(boolean enabled, Source source) {
    }

    private static final long DEFAULT_TTL_MILLIS = 5_000L;

    private final SystemSettingService settings;
    private final Boolean configured;
    private final long ttlMillis;

    private volatile Effective cached;
    private volatile long cachedAtMillis;
    private volatile long cachedRevision;

    @Autowired
    public UpdateCheckResolver(SystemSettingService settings,
                               @Value("${app.update-check.enabled:}") String configured) {
        this(settings, configured, DEFAULT_TTL_MILLIS);
    }

    UpdateCheckResolver(SystemSettingService settings, String configured, long ttlMillis) {
        this.settings = settings;
        this.configured = parseExplicit(configured);
        this.ttlMillis = ttlMillis;
    }

    public Effective effective() {
        if (configured != null) {
            return new Effective(configured, Source.CONFIG);
        }
        long revision = settings.revision();
        long now = System.currentTimeMillis();
        Effective current = cached;
        if (current != null && revision == cachedRevision && now - cachedAtMillis < ttlMillis) {
            return current;
        }
        Effective resolved = settings.findBoolean(SystemSettingKey.UPDATE_CHECK_ENABLED)
                .map(value -> new Effective(value, Source.DATABASE))
                .orElseGet(() -> new Effective(true, Source.DEFAULT));
        cached = resolved;
        cachedAtMillis = now;
        cachedRevision = revision;
        return resolved;
    }

    public boolean isEnabled() {
        return effective().enabled();
    }

    /** True when an explicit configuration value pins the switch, so the page is read-only. */
    public boolean isConfigControlled() {
        return configured != null;
    }

    private static Boolean parseExplicit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return SystemSettingService.parseBoolean(raw).orElseThrow(() -> new IllegalArgumentException(
                "app.update-check.enabled must be one of true/false/on/off/yes/no/1/0, was '"
                        + raw.trim() + "'"));
    }
}
