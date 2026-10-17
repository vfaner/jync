package com.qqmu.jync.service.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.SyncConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies the project's column-cleansing rules to one table's row maps before they are
 * written to the target.
 *
 * <p>Scope is intentionally narrow: only values that are already {@link String}s are
 * touched, so numbers and dates are never coerced implicitly. A rule with a missing or
 * invalid argument is a no-op at runtime — the save path validates the rule, and a
 * damaged stored config must not stop an entire sync.
 */
@Slf4j
public final class ColumnCleanser {

    /** Rules for one table, with column names and regexes resolved once per batch. */
    private record Rule(String column, ColumnRuleType type, String arg, String replacement,
                        Pattern pattern) {

        Object apply(String value) {
            return switch (type) {
                case TRIM -> value.strip();
                case UPPER -> value.toUpperCase(Locale.ROOT);
                case LOWER -> value.toLowerCase(Locale.ROOT);
                case BLANK_TO_NULL -> value.isBlank() ? null : value;
                case STRIP_PREFIX -> value.startsWith(arg) ? value.substring(arg.length()) : value;
                case STRIP_SUFFIX -> value.endsWith(arg)
                        ? value.substring(0, value.length() - arg.length()) : value;
                case REGEX_REPLACE -> pattern.matcher(value)
                        .replaceAll(replacement == null ? "" : replacement);
            };
        }
    }

    private final List<Rule> rules;

    private ColumnCleanser(List<Rule> rules) {
        this.rules = rules;
    }

    /** Builds the rule list for one source table; an absent table matches nothing. */
    public static ColumnCleanser forTable(SyncConfig config, String table) {
        List<Rule> rules = new ArrayList<>();
        if (config == null || config.getColumnRules() == null || table == null) {
            return new ColumnCleanser(rules);
        }
        for (ColumnRule rule : config.getColumnRules()) {
            if (rule == null || rule.getType() == null
                    || rule.getColumn() == null || rule.getColumn().isBlank()
                    || !table.equalsIgnoreCase(rule.getTable())) {
                continue;
            }
            String arg = rule.getArg();
            boolean argRequired = rule.getType() == ColumnRuleType.STRIP_PREFIX
                    || rule.getType() == ColumnRuleType.STRIP_SUFFIX
                    || rule.getType() == ColumnRuleType.REGEX_REPLACE;
            if (argRequired && (arg == null || arg.isEmpty())) {
                continue;
            }
            Pattern pattern = null;
            if (rule.getType() == ColumnRuleType.REGEX_REPLACE) {
                try {
                    pattern = Pattern.compile(arg);
                } catch (PatternSyntaxException e) {
                    log.warn("Ignoring column rule for {}.{}: invalid regular expression '{}'",
                            table, rule.getColumn(), arg);
                    continue;
                }
            }
            rules.add(new Rule(rule.getColumn(), rule.getType(), arg, rule.getReplacement(),
                    pattern));
        }
        return new ColumnCleanser(rules);
    }

    /**
     * Returns the same row map with configured columns transformed. Keys are matched
     * case-insensitively, like the database identifiers they came from.
     */
    public Map<String, Object> apply(Map<String, Object> row) {
        if (row == null || rules.isEmpty()) {
            return row;
        }
        for (Rule rule : rules) {
            String key = findKey(row, rule.column());
            if (key == null) {
                continue;
            }
            Object value = row.get(key);
            if (!(value instanceof String)) {
                continue;
            }
            row.put(key, rule.apply((String) value));
        }
        return row;
    }

    private static String findKey(Map<String, Object> row, String column) {
        for (String key : row.keySet()) {
            if (key != null && key.equalsIgnoreCase(column)) {
                return key;
            }
        }
        return null;
    }
}
