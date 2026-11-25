package com.qqmu.jync.service.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.SystemSetting;
import com.qqmu.jync.repository.SystemSettingRepository;
import com.qqmu.jync.service.auth.AccessGuard;

import lombok.extern.slf4j.Slf4j;

/**
 * Administrator-editable overrides for a closed set of hot system performance knobs.
 *
 * <p>This is intentionally not "any {@code sync.*} property". The whitelist is limited to
 * batch/fetch sizes, chunking and retry count — values the engine re-reads for each new cycle
 * or load and that cannot delete data or change sync semantics. Security, TLS, credentials,
 * retention, comparison limits, pool sizes, thread counts and CDC transaction limits stay in
 * {@code application.yml}.
 *
 * <p>Overrides live in the existing {@code system_setting} table, so they survive a restart and
 * are exported with the configuration package. The base value is the bound
 * {@link SyncProperties} value captured at startup; a missing or invalid row falls back to it.
 * A scheduled refresh applies rows written by another node (or by a configuration import), so
 * the cluster converges without a message bus.
 */
@Service
@Slf4j
public class SystemTuningService {

    public enum Kind { INT, LONG, BOOLEAN }

    public enum Tunable {
        BATCH_SIZE("sync.batch-size", Kind.INT, 1, 20_000),
        FULL_LOAD_BATCH_SIZE("sync.full-load-batch-size", Kind.INT, 1, 20_000),
        FETCH_SIZE("sync.fetch-size", Kind.INT, 1, 100_000),
        FULL_LOAD_FETCH_SIZE("sync.full-load-fetch-size", Kind.INT, 1, 100_000),
        CHUNK_SIZE("sync.chunk-size", Kind.INT, 1_000, 1_000_000),
        CHUNK_TARGET_BYTES("sync.chunk-target-bytes", Kind.LONG,
                1_048_576L, 1_073_741_824L),
        ADAPTIVE_CHUNK_SIZE("sync.adaptive-chunk-size", Kind.BOOLEAN, 0, 1),
        MIN_ADAPTIVE_CHUNK_SIZE("sync.min-adaptive-chunk-size", Kind.INT, 100, 100_000),
        MAX_RETRIES("sync.max-retries", Kind.INT, 0, 10),
        /**
         * Fan-out targets per project, primary included. A deployment with a genuine
         * one-to-many topology (1 → 25) can raise it without a code change; each target
         * costs one worker, one target pool seat and one queue/consumer group.
         */
        FANOUT_MAX_TARGETS("sync.fanout.max-targets", Kind.INT, 2, 100);

        private final String property;
        private final Kind kind;
        private final long min;
        private final long max;

        Tunable(String property, Kind kind, long min, long max) {
            this.property = property;
            this.kind = kind;
            this.min = min;
            this.max = max;
        }

        public String property() {
            return property;
        }

        public Kind kind() {
            return kind;
        }

        public long min() {
            return min;
        }

        public long max() {
            return max;
        }

        static Tunable byKey(String key) {
            if (key == null) {
                return null;
            }
            String trimmed = key.trim();
            for (Tunable tunable : values()) {
                if (tunable.property.equals(trimmed)
                        || tunable.property.equals("sync." + trimmed)) {
                    return tunable;
                }
            }
            return null;
        }

        String read(SyncProperties properties) {
            return switch (this) {
                case BATCH_SIZE -> String.valueOf(properties.getBatchSize());
                case FULL_LOAD_BATCH_SIZE -> String.valueOf(properties.getFullLoadBatchSize());
                case FETCH_SIZE -> String.valueOf(properties.getFetchSize());
                case FULL_LOAD_FETCH_SIZE -> String.valueOf(properties.getFullLoadFetchSize());
                case CHUNK_SIZE -> String.valueOf(properties.getChunkSize());
                case CHUNK_TARGET_BYTES -> String.valueOf(properties.getChunkTargetBytes());
                case ADAPTIVE_CHUNK_SIZE -> String.valueOf(properties.isAdaptiveChunkSize());
                case MIN_ADAPTIVE_CHUNK_SIZE ->
                        String.valueOf(properties.getMinAdaptiveChunkSize());
                case MAX_RETRIES -> String.valueOf(properties.getMaxRetries());
                case FANOUT_MAX_TARGETS ->
                        String.valueOf(properties.getFanout().getMaxTargets());
            };
        }

        void write(SyncProperties properties, String value) {
            switch (this) {
                case BATCH_SIZE -> properties.setBatchSize(Integer.parseInt(value));
                case FULL_LOAD_BATCH_SIZE ->
                        properties.setFullLoadBatchSize(Integer.parseInt(value));
                case FETCH_SIZE -> properties.setFetchSize(Integer.parseInt(value));
                case FULL_LOAD_FETCH_SIZE ->
                        properties.setFullLoadFetchSize(Integer.parseInt(value));
                case CHUNK_SIZE -> properties.setChunkSize(Integer.parseInt(value));
                case CHUNK_TARGET_BYTES -> properties.setChunkTargetBytes(Long.parseLong(value));
                case ADAPTIVE_CHUNK_SIZE -> properties.setAdaptiveChunkSize(
                        Boolean.parseBoolean(value));
                case MIN_ADAPTIVE_CHUNK_SIZE ->
                        properties.setMinAdaptiveChunkSize(Integer.parseInt(value));
                case MAX_RETRIES -> properties.setMaxRetries(Integer.parseInt(value));
                case FANOUT_MAX_TARGETS -> properties.getFanout()
                        .setMaxTargets(Integer.parseInt(value));
            }
        }

        /** Returns the normalized text or throws an i18n-keyed refusal. */
        String normalize(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("error.system.param.invalid");
            }
            if (kind == Kind.BOOLEAN) {
                return SystemSettingService.parseBoolean(raw)
                        .map(String::valueOf)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "error.system.param.invalid"));
            }
            long value;
            try {
                value = kind == Kind.INT
                        ? Integer.parseInt(raw.trim())
                        : Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("error.system.param.invalid");
            }
            if (value < min || value > max) {
                throw new IllegalArgumentException("error.system.param.range");
            }
            return String.valueOf(value);
        }
    }

    /** One row of the admin read tool. */
    public record Item(String key, String value, String base, boolean overridden,
                       String type, Long min, Long max) {
    }

    /** One old/new row on the confirmation card. */
    public record Change(Tunable tunable, String before, String after,
                         boolean beforeIsDefault, boolean afterIsDefault) {
        public String key() {
            return tunable.property();
        }
    }

    private final SyncProperties properties;
    private final SystemSettingRepository repository;
    private final SystemSettingService settings;
    private final Map<String, String> base = new LinkedHashMap<>();
    private final Map<String, String> applied = new LinkedHashMap<>();
    private final Set<String> invalidWarned = ConcurrentHashMap.newKeySet();

    public SystemTuningService(SyncProperties properties, SystemSettingRepository repository,
                               SystemSettingService settings) {
        this.properties = properties;
        this.repository = repository;
        this.settings = settings;
        for (Tunable tunable : Tunable.values()) {
            base.put(tunable.property, tunable.read(properties));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            refresh();
        } catch (RuntimeException e) {
            // A missing/unreachable settings row must not fail startup; the YAML base stays.
            log.warn("Could not load system tuning overrides at startup: {}", e.toString());
        }
    }

    /**
     * Multi-node / configuration-import convergence. Rows are re-read every 30 seconds; a
     * local save applies immediately through {@link #apply}.
     */
    @Scheduled(initialDelay = 30_000L, fixedDelay = 30_000L)
    public synchronized void refresh() {
        Map<String, String> next = readOverrides();
        if (next.equals(applied)) {
            return;
        }
        applyOverrides(next);
    }

    public synchronized List<Item> items() {
        Map<String, String> overrides = readOverrides();
        List<Item> out = new ArrayList<>();
        for (Tunable tunable : Tunable.values()) {
            boolean overridden = overrides.containsKey(tunable.property);
            out.add(new Item(tunable.property,
                    overridden ? overrides.get(tunable.property) : base.get(tunable.property),
                    base.get(tunable.property), overridden, tunable.kind.name(),
                    tunable.kind == Kind.BOOLEAN ? null : tunable.min,
                    tunable.kind == Kind.BOOLEAN ? null : tunable.max));
        }
        return out;
    }

    /**
     * Validation and card preview. Nothing is written; the same method runs again at click
     * time against the fresh values.
     */
    public synchronized List<Change> preview(Map<String, String> set, List<String> reset) {
        Map<String, String> setting = set == null ? Map.of() : set;
        List<String> clearing = reset == null ? List.of() : reset;
        if (setting.isEmpty() && clearing.isEmpty()) {
            throw new IllegalArgumentException("error.system.param.empty");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String key : setting.keySet()) {
            if (!seen.add(canonical(key))) {
                throw new IllegalArgumentException("error.system.param.conflict");
            }
        }
        for (String key : clearing) {
            if (!seen.add(canonical(key))) {
                throw new IllegalArgumentException("error.system.param.conflict");
            }
        }
        if (seen.size() > Tunable.values().length) {
            throw new IllegalArgumentException("error.system.param.tooMany");
        }

        Map<String, String> overrides = readOverrides();
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : setting.entrySet()) {
            Tunable tunable = require(entry.getKey());
            String after = tunable.normalize(entry.getValue());
            String before = effective(tunable, overrides);
            changes.add(new Change(tunable, before, after,
                    !overrides.containsKey(tunable.property), false));
        }
        for (String raw : clearing) {
            Tunable tunable = require(raw);
            String before = effective(tunable, overrides);
            if (!overrides.containsKey(tunable.property)) {
                continue;
            }
            changes.add(new Change(tunable, before, base.get(tunable.property), false, true));
        }
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("error.system.param.unchanged");
        }
        return changes;
    }

    /** Applies the card's changes; admin-only, re-validated against the current values. */
    @Transactional
    public synchronized void apply(Map<String, String> set, List<String> reset,
                                   AccessGuard actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new AccessDeniedException(AccessGuard.DENIED_KEY);
        }
        // Preview first: a rejected range must not leave a partial write behind.
        List<Change> changes = preview(set, reset);
        for (Change change : changes) {
            if (change.afterIsDefault()) {
                settings.removeValue(change.key());
            } else {
                settings.putValue(change.key(), change.after());
            }
        }
        applyOverrides(readOverrides());
    }

    private Map<String, String> readOverrides() {
        Map<String, String> out = new LinkedHashMap<>();
        for (SystemSetting row : repository.findAll()) {
            Tunable tunable = Tunable.byKey(row.getKey());
            if (tunable == null) {
                continue;
            }
            try {
                out.put(tunable.property, tunable.normalize(row.getValue()));
            } catch (IllegalArgumentException e) {
                // A hand-edited or imported row must not break startup; fall back to YAML.
                // Warn once per distinct bad value instead of every 30-second refresh.
                if (invalidWarned.add(row.getKey() + "=" + row.getValue())) {
                    log.warn("Ignoring invalid system tuning override {}={}", row.getKey(),
                            row.getValue());
                }
            }
        }
        return out;
    }

    private void applyOverrides(Map<String, String> overrides) {
        for (Tunable tunable : Tunable.values()) {
            String value = overrides.getOrDefault(tunable.property, base.get(tunable.property));
            try {
                tunable.write(properties, value);
            } catch (RuntimeException e) {
                // Defensive: normalize() should have made this impossible.
                log.warn("Could not apply system tuning {}={}; keeping base", tunable.property,
                        value);
                tunable.write(properties, base.get(tunable.property));
                overrides.remove(tunable.property);
            }
        }
        if (!applied.equals(overrides)) {
            log.info("Applied system tuning overrides: {}", overrides);
        }
        applied.clear();
        applied.putAll(overrides);
    }

    private String effective(Tunable tunable, Map<String, String> overrides) {
        return overrides.getOrDefault(tunable.property, base.get(tunable.property));
    }

    private Tunable require(String key) {
        Tunable tunable = Tunable.byKey(key);
        if (tunable == null) {
            throw new IllegalArgumentException("error.system.param.notAllowed");
        }
        return tunable;
    }

    private String canonical(String key) {
        return require(key).property;
    }

}
