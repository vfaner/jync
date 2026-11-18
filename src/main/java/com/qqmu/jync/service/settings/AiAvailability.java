package com.qqmu.jync.service.settings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.service.ai.AiDisabledException;

/**
 * Decides whether the AI assistant is available.
 *
 * <p>Priority, highest first:
 *
 * <ol>
 *   <li>{@code sync.ai.enabled=false} — the deployment's hard off, always wins and cannot be
 *       flipped back from the page;</li>
 *   <li>the value saved from the 系统配置 page;</li>
 *   <li>{@code true} — a fresh installation may use AI unless someone says otherwise.</li>
 * </ol>
 *
 * <p>The database lookup is cached for a few seconds because the layout asks on every render
 * (the menu and the dashboard prompt both read it); a write on this node bumps the service
 * revision and takes effect on the next request, while other nodes converge when their TTL
 * lapses.
 */
@Component
public class AiAvailability {

    private static final long DEFAULT_TTL_MILLIS = 5_000L;

    private final SystemSettingService settings;
    private final boolean configured;
    private final long ttlMillis;

    private volatile Boolean cached;
    private volatile long cachedAtMillis;
    private volatile long cachedRevision;

    @Autowired
    public AiAvailability(SystemSettingService settings, SyncProperties properties) {
        this(settings, properties.getAi().isEnabled(), DEFAULT_TTL_MILLIS);
    }

    /** Test seam: the TTL is short enough to exercise without sleeping for a real expiry. */
    AiAvailability(SystemSettingService settings, boolean configured, long ttlMillis) {
        this.settings = settings;
        this.configured = configured;
        this.ttlMillis = ttlMillis;
    }

    /** Deployment-level hard off: {@code sync.ai.enabled=false}. It always wins. */
    public boolean isHardDisabled() {
        return !configured;
    }

    public boolean isEnabled() {
        if (!configured) {
            return false;
        }
        long revision = settings.revision();
        long now = System.currentTimeMillis();
        Boolean current = cached;
        if (current != null && revision == cachedRevision && now - cachedAtMillis < ttlMillis) {
            return current;
        }
        boolean resolved = settings.findBoolean(SystemSettingKey.AI_ENABLED).orElse(true);
        cached = resolved;
        cachedAtMillis = now;
        cachedRevision = revision;
        return resolved;
    }

    public void requireEnabled() {
        if (!isEnabled()) {
            throw new AiDisabledException();
        }
    }
}
