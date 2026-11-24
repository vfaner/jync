package com.qqmu.jync.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.quartz.CronExpression;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.service.cdc.CdcReconnectBackoff;
import com.qqmu.jync.service.task.FullLoadCapacity;

/**
 * The closed set of project parameters the AI assistant may change through a confirmation
 * card.
 *
 * <p>This is deliberately not "any field in {@link SyncConfig}". The numeric whitelist and the
 * cursor-column/schedule rules live here; table selection, capture strategy, the
 * delete/truncate switch, column cleansing/masking and DDL overrides change what data ends up
 * in the target and remain page-only decisions. The bounds mirror the form and the engine
 * clamps ({@code ProjectController}, {@link FullLoadCapacity}, {@link CdcReconnectBackoff});
 * the assistant gets a hard refusal rather than a silent clamp so its explanation matches
 * what was actually saved.
 *
 * <p>Applicability follows the current strategy: a poll interval on a log-CDC project is a
 * dead field the page hides, and the idle interval only exists for the engines that poll
 * their change stream (binlog is push-based, polling/compare have no consumer at all).
 */
public final class ProjectTuning {

    public static final int MAX_BATCH_SIZE = 20_000;
    public static final int MAX_PARALLELISM = FullLoadCapacity.MAX_PARALLELISM;
    public static final long MIN_POLL_INTERVAL_MS = 200L;
    public static final long MAX_POLL_INTERVAL_MS = 86_400_000L;
    public static final long MIN_CDC_IDLE_POLL_MS = CdcReconnectBackoff.MIN_IDLE_POLL_MS;
    public static final long MAX_CDC_IDLE_POLL_MS = 86_400_000L;

    public enum Field {
        BATCH_SIZE("batchSize", "option.batchSize", 1, MAX_BATCH_SIZE),
        FULL_LOAD_PARALLELISM("fullLoadParallelism", "option.fullLoadParallelism",
                1, MAX_PARALLELISM),
        POLL_INTERVAL_MS("pollIntervalMs", "option.pollInterval",
                MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS),
        CDC_IDLE_POLL_MS("cdcIdlePollMs", "option.cdcIdlePoll",
                MIN_CDC_IDLE_POLL_MS, MAX_CDC_IDLE_POLL_MS);

        private final String key;
        private final String labelKey;
        private final long min;
        private final long max;

        Field(String key, String labelKey, long min, long max) {
            this.key = key;
            this.labelKey = labelKey;
            this.min = min;
            this.max = max;
        }

        public String key() {
            return key;
        }

        public String labelKey() {
            return labelKey;
        }

        static Field byKey(String key) {
            for (Field field : values()) {
                if (field.key.equals(key)) {
                    return field;
                }
            }
            return null;
        }
    }

    /** One accepted change: what the config holds now and what it will hold after the click. */
    public record Change(Field field, Long before, Long after) {
    }

    private ProjectTuning() {
    }

    /**
     * Validates a change set against the current config and strategy. Throws an i18n-keyed
     * exception the assistant can explain verbatim; nothing is written.
     */
    public static List<Change> validate(SyncConfig config, SyncTargetKind targetKind,
                                        Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("error.support.param.empty");
        }
        CaptureStrategy strategy = config.getCaptureStrategyOrDefault();
        List<Change> out = new ArrayList<>();
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            Field field = Field.byKey(entry.getKey());
            if (field == null) {
                throw new IllegalArgumentException("error.support.param.notAllowed");
            }
            long value = integral(entry.getValue());
            if (value < field.min || value > field.max) {
                throw new IllegalArgumentException("error.support.param.range");
            }
            if (field == Field.POLL_INTERVAL_MS && targetKind == SyncTargetKind.REDIS) {
                throw new IllegalArgumentException("error.support.param.target");
            }
            if (!appliesTo(field, strategy, targetKind)) {
                throw new IllegalArgumentException("error.support.param.strategy");
            }
            out.add(new Change(field, configured(config, field), value));
        }
        return out;
    }

    /** Convenience for callers with no Redis target; the tool and service pass the real kind. */
    public static List<Change> validate(SyncConfig config, Map<String, Object> changes) {
        return validate(config, SyncTargetKind.JDBC, changes);
    }

    public static void apply(SyncConfig config, List<Change> changes) {
        for (Change change : changes) {
            long value = change.after();
            switch (change.field()) {
                case BATCH_SIZE -> config.setBatchSize((int) value);
                case FULL_LOAD_PARALLELISM -> config.setFullLoadParallelism((int) value);
                case POLL_INTERVAL_MS -> config.setPollIntervalMs(value);
                case CDC_IDLE_POLL_MS -> config.setCdcIdlePollMs(value);
            }
        }
    }

    /** Configured values only; null means "use the global / engine default". */
    public static Map<String, Object> view(SyncConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field field : Field.values()) {
            out.put(field.key(), configured(config, field));
        }
        return out;
    }

    /** The engine uses the configured value only when it is positive. */
    private static Long configured(SyncConfig config, Field field) {
        Long value = switch (field) {
            case BATCH_SIZE -> config.getBatchSize() == null
                    ? null : config.getBatchSize().longValue();
            case FULL_LOAD_PARALLELISM -> config.getFullLoadParallelism() == null
                    ? null : config.getFullLoadParallelism().longValue();
            case POLL_INTERVAL_MS -> config.getPollIntervalMs();
            case CDC_IDLE_POLL_MS -> config.getCdcIdlePollMs();
        };
        return value == null || value <= 0 ? null : value;
    }

    private static boolean appliesTo(Field field, CaptureStrategy strategy,
                                     SyncTargetKind targetKind) {
        return switch (field) {
            case BATCH_SIZE, FULL_LOAD_PARALLELISM -> true;
            // A Redis target has no Quartz trigger at all: the interval would be dead config.
            case POLL_INTERVAL_MS -> !strategy.isLogCdc()
                    && targetKind != SyncTargetKind.REDIS;
            case CDC_IDLE_POLL_MS -> strategy == CaptureStrategy.LOGMINER_CDC
                    || strategy == CaptureStrategy.PG_LOGICAL_CDC
                    || strategy == CaptureStrategy.SQLSERVER_CDC
                    || strategy == CaptureStrategy.YSTREAM_CDC;
        };
    }

    private static long integral(Object raw) {
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("error.support.param.invalid");
        }
        double value = number.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)
                || value < Long.MIN_VALUE || value > Long.MAX_VALUE) {
            throw new IllegalArgumentException("error.support.param.invalid");
        }
        return number.longValue();
    }
    // --- cursor columns and schedule -------------------------------------------------------

    /** Cursor columns only affect the polling path; the same rule as the page's cards. */
    public static void requireCursorApplicable(SyncConfig config, SyncTargetKind targetKind) {
        CaptureStrategy strategy = config.getCaptureStrategyOrDefault();
        if (strategy.isLogCdc() || strategy == CaptureStrategy.FULL_COMPARE
                || targetKind == SyncTargetKind.REDIS) {
            // IllegalArgumentException so a tool call surfaces the specific key instead of
            // the registry's generic "tool failed".
            throw new IllegalArgumentException("error.project.cursor.not.polling");
        }
    }

    /** Log CDC has no Quartz trigger, and a Redis target has no JDBC cycle to schedule. */
    public static void requireScheduleApplicable(SyncConfig config, SyncTargetKind targetKind) {
        if (config.getCaptureStrategyOrDefault().isLogCdc()
                || targetKind == SyncTargetKind.REDIS) {
            throw new IllegalArgumentException("error.project.schedule.not.polling");
        }
    }

    /**
     * Table to cursor-column overrides. Blank column means "remove the override and go back
     * to auto-detection"; the table must be in the saved selection so a typo cannot create a
     * row that will never be used.
     */
    public static Map<String, String> normalizeColumns(SyncConfig config,
                                                        Map<String, String> columns) {
        Map<String, String> out = new LinkedHashMap<>();
        if (columns == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            String table = entry.getKey() == null ? "" : entry.getKey().trim();
            if (table.isEmpty() || table.length() > 128) {
                throw new IllegalArgumentException("error.project.cursor.table.invalid");
            }
            if (!config.includesTable(table)) {
                throw new IllegalArgumentException("error.project.cursor.table.notSelected");
            }
            String canonical = canonicalTable(config, table);
            if (canonical != null) {
                table = canonical;
            }
            String column = entry.getValue() == null ? "" : entry.getValue().trim();
            if (column.length() > 128) {
                throw new IllegalArgumentException("error.project.cursor.priority.invalid");
            }
            out.put(table, column);
        }
        return out;
    }

    /**
     * The selected table's stored spelling. Progress and chunk-checkpoint rows are looked up
     * by exact object name, so a cursor clear must use this spelling, not the model's casing.
     */
    public static String canonicalTable(SyncConfig config, String name) {
        if (config == null || name == null || config.getTables() == null) {
            return null;
        }
        for (String table : config.getTables()) {
            if (table != null && table.equalsIgnoreCase(name.trim())) {
                return table;
            }
        }
        return null;
    }

    /** Dedupe case-insensitively, keep the first spelling, cap at 20 — same as the page. */
    public static List<String> normalizePriority(List<String> values) {
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (values != null) {
            for (String raw : values) {
                String value = raw == null ? "" : raw.trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (value.length() > 128) {
                    throw new IllegalArgumentException("error.project.cursor.priority.invalid");
                }
                if (seen.add(value.toUpperCase(Locale.ROOT))) {
                    normalized.add(value);
                }
            }
        }
        if (normalized.size() > 20) {
            throw new IllegalArgumentException("error.project.cursor.priority.too.many");
        }
        return normalized;
    }

    public static boolean samePriority(List<String> left, List<String> right) {
        if (left == null || right == null || left.size() != right.size()) {
            return left == right;
        }
        for (int i = 0; i < left.size(); i++) {
            String l = left.get(i);
            String r = right.get(i);
            if (l == null ? r != null : !l.equalsIgnoreCase(r)) {
                return false;
            }
        }
        return true;
    }

    /** Blank clears the cron and returns the project to interval scheduling. */
    public static String normalizeCron(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cron = raw.trim();
        if (cron.length() > 128 || !CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("error.project.cron.invalid");
        }
        return cron;
    }

}
