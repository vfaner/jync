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
                // Matcher parses the replacement lazily, at the first match, so an invalid one
                // would compile cleanly here and then throw out of apply() on the first row
                // that matches — stopping the table. Rules saved before the wizard validated
                // this, or written straight into the metadata store, get skipped like a bad
                // pattern instead.
                if (!isValidReplacement(pattern, rule.getReplacement())) {
                    log.warn("Ignoring column rule for {}.{}: invalid replacement '{}'",
                            table, rule.getColumn(), rule.getReplacement());
                    continue;
                }
            }
            rules.add(new Rule(rule.getColumn(), rule.getType(), arg, rule.getReplacement(),
                    pattern));
        }
        return new ColumnCleanser(rules);
    }

    /**
     * Columns that carry at least one active rule, in the order the rules were saved.
     * The reader forces these inline: {@link #apply(Map)} only touches {@code String}
     * values, so a streamed value would silently skip its cleansing rule.
     */
    public List<String> columns() {
        List<String> names = new ArrayList<>(rules.size());
        for (Rule rule : rules) {
            names.add(rule.column());
        }
        return List.copyOf(names);
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

    /**
     * Mirrors the parts of {@link java.util.regex.Matcher#appendReplacement} that can fail:
     * a trailing backslash, a {@code $} without a group reference, and a reference to a group
     * the pattern does not define. Escaped characters ({@code \$}, {@code \\}) are literal.
     */
    public static boolean isValidReplacement(Pattern pattern, String replacement) {
        if (replacement == null) {
            return true;
        }
        int groupCount = pattern.matcher("").groupCount();
        java.util.Set<String> namedGroups = namedGroups(pattern);
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                if (++i >= replacement.length()) {
                    return false;
                }
                continue;
            }
            if (c != '$') {
                continue;
            }
            if (++i >= replacement.length()) {
                return false;
            }
            char next = replacement.charAt(i);
            if (next == '{') {
                int close = replacement.indexOf('}', i);
                if (close < 0) {
                    return false;
                }
                // Java reads ${...} as a NAMED group only; even ${1} is an error.
                if (!namedGroups.contains(replacement.substring(i + 1, close))) {
                    return false;
                }
                i = close;
            } else if (Character.isDigit(next)) {
                int end = i;
                while (end + 1 < replacement.length()
                        && Character.isDigit(replacement.charAt(end + 1))) {
                    end++;
                }
                if (!hasValidGroupPrefix(replacement, i, end, groupCount)) {
                    return false;
                }
                i = end;
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the digit run contains a group reference Java accepts.
     *
     * <p>Java consumes the whole run and, when that number exceeds the group count, backs off
     * one digit at a time until the remaining prefix is a valid group; the dropped digits are
     * literal ("$12" with one group is group 1 followed by "2"). Parsed without an int to
     * keep an overlong run ({@code $99999999999999999999}) a validation error, not a 500.
     */
    private static boolean hasValidGroupPrefix(String replacement, int from, int to,
                                               int groupCount) {
        for (int end = to; end >= from; end--) {
            long value = 0;
            boolean overflow = false;
            for (int i = from; i <= end; i++) {
                value = value * 10 + (replacement.charAt(i) - '0');
                if (value > Integer.MAX_VALUE) {
                    overflow = true;
                    break;
                }
            }
            if (!overflow && value <= groupCount) {
                return true;
            }
        }
        return false;
    }

    /** Named groups the pattern defines; lookbehind's {@code (?<=}/{@code (?<!} is excluded. */
    private static java.util.Set<String> namedGroups(Pattern pattern) {
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.regex.Matcher m = Pattern
                .compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>")
                .matcher(pattern.pattern());
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
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
