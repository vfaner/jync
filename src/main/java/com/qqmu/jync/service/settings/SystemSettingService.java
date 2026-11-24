package com.qqmu.jync.service.settings;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.SystemSetting;
import com.qqmu.jync.repository.SystemSettingRepository;

/**
 * Reads and writes the page-editable switches.
 *
 * <p>Every write bumps a revision counter. Readers that cache the value (the HTTPS resolver
 * consults one on every request) compare against it, so a save on this node takes effect on
 * the very next request instead of waiting for the cache to expire; other nodes converge when
 * their TTL lapses.
 */
@Service
public class SystemSettingService {

    private final SystemSettingRepository repository;
    private final AtomicLong revision = new AtomicLong();

    public SystemSettingService(SystemSettingRepository repository) {
        this.repository = repository;
    }

    public Optional<String> find(SystemSettingKey key) {
        return repository.findById(key.key()).map(SystemSetting::getValue);
    }

    public Optional<Boolean> findBoolean(SystemSettingKey key) {
        return find(key).flatMap(SystemSettingService::parseBoolean);
    }

    /** Raw value for settings whose key is not part of the boolean enum (system tuning). */
    public Optional<String> findValue(String key) {
        return key == null ? Optional.empty()
                : repository.findById(key).map(SystemSetting::getValue);
    }

    /**
     * A hand-edited database row must not break the application: an unrecognised value reads
     * as absent, and the caller's fallback applies.
     */
    public boolean getBoolean(SystemSettingKey key, boolean fallback) {
        return findBoolean(key).orElse(fallback);
    }

    /**
     * Stores a boolean and returns the value it replaced.
     *
     * <p>An absent or unparseable previous value comes back empty, which callers report as
     * "no prior setting" rather than inventing one.
     */
    @Transactional
    public Optional<Boolean> putBoolean(SystemSettingKey key, boolean value) {
        SystemSetting row = repository.findById(key.key()).orElseGet(() -> {
            SystemSetting created = new SystemSetting();
            created.setKey(key.key());
            return created;
        });
        Optional<Boolean> previous = parseBoolean(row.getValue());
        row.setValue(Boolean.toString(value));
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        revision.incrementAndGet();
        return previous;
    }

    /**
     * Stores a raw value and returns the value it replaced. The key set is still closed at
     * the caller (see the tuning whitelist); this method only owns persistence and revision.
     */
    @Transactional
    public Optional<String> putValue(String key, String value) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new IllegalArgumentException("error.systemConfig.setting.invalid");
        }
        SystemSetting row = repository.findById(key).orElseGet(() -> {
            SystemSetting created = new SystemSetting();
            created.setKey(key);
            return created;
        });
        Optional<String> previous = Optional.ofNullable(row.getValue());
        row.setValue(value);
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        revision.incrementAndGet();
        return previous;
    }

    /** Removes an override so the resolver falls back to application.yml. */
    @Transactional
    public void removeValue(String key) {
        if (key != null && repository.existsById(key)) {
            repository.deleteById(key);
            revision.incrementAndGet();
        }
    }

    /** Monotonic; changes whenever this node writes a setting. */
    public long revision() {
        return revision.get();
    }

    /**
     * Announces that a setting was written outside this service — the configuration-package
     * import saves rows through the repository directly. Caches on this node refresh on the
     * next request instead of waiting for their TTL.
     */
    public void markChanged() {
        revision.incrementAndGet();
    }

    static Optional<Boolean> parseBoolean(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> {
                return Optional.of(true);
            }
            case "false", "off", "no", "0" -> {
                return Optional.of(false);
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * An explicit switch value, or null when unset. A value that is present but not a boolean
     * fails naming the property, so the operator sees which setting to fix.
     */
    static Boolean parseExplicit(String raw, String propertyName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseBoolean(raw).orElseThrow(() -> new IllegalArgumentException(
                propertyName + " must be one of true/false/on/off/yes/no/1/0, was '"
                        + raw.trim() + "'"));
    }
}
