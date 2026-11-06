package com.qqmu.jync.service.settings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether plaintext requests are refused.
 *
 * <p>Priority, highest first:
 *
 * <ol>
 *   <li>an explicit configuration value ({@code JYN_REQUIRE_HTTPS} or
 *       {@code app.security.require-https}) — always wins, and a typo fails the boot;</li>
 *   <li>the value saved from the 系统配置 page;</li>
 *   <li>{@code false}.</li>
 * </ol>
 *
 * <p>The database lookup is cached for a few seconds because the filter asks on every
 * plaintext request, and the default deployment is plaintext. A write on this node bumps the
 * service revision, which invalidates the cache immediately; other nodes pick the change up
 * within the TTL.
 */
@Component
public class RequireHttpsResolver {

    public enum Source { CONFIG, DATABASE, DEFAULT }

    public record Effective(boolean required, Source source) {
    }

    private static final long DEFAULT_TTL_MILLIS = 5_000L;

    private final SystemSettingService settings;
    private final Boolean configured;
    private final long ttlMillis;

    private volatile Effective cached;
    private volatile long cachedAtMillis;
    private volatile long cachedRevision;

    @Autowired
    public RequireHttpsResolver(SystemSettingService settings,
                                @Value("${app.security.require-https:}") String configured) {
        this(settings, configured, DEFAULT_TTL_MILLIS);
    }

    RequireHttpsResolver(SystemSettingService settings, String configured, long ttlMillis) {
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
        Effective resolved = settings.findBoolean(SystemSettingKey.SECURITY_REQUIRE_HTTPS)
                .map(value -> new Effective(value, Source.DATABASE))
                .orElseGet(() -> new Effective(false, Source.DEFAULT));
        cached = resolved;
        cachedAtMillis = now;
        cachedRevision = revision;
        return resolved;
    }

    public boolean isRequired() {
        return effective().required();
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
                "app.security.require-https must be one of true/false/on/off/yes/no/1/0, was '"
                        + raw.trim() + "'"));
    }
}
