package com.qqmu.jync.service.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The glob dialect shared by every dedicated-migration source: comma- or newline-separated
 * patterns, {@code *} for any run and {@code ?} for one character. A blank pattern means
 * "everything".
 */
public final class MigrationGlob {

    private MigrationGlob() {
    }

    public static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : text.split("[,\\r\\n]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    public static boolean matches(String value, String glob) {
        if (glob == null || glob.isEmpty()) {
            return true;
        }
        return Pattern.compile("^" + regex(glob) + "$").matcher(value).matches();
    }

    public static boolean matchesAny(String value, String patternText) {
        List<String> patterns = split(patternText);
        if (patterns.isEmpty()) {
            return true;
        }
        if (patterns.size() == 1) {
            return matches(value, patterns.get(0));
        }
        // One alternation instead of one Pattern per pattern: a long list is matched against
        // every discovered collection, so compiling once per call matters.
        StringBuilder alternation = new StringBuilder("^(?:");
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) {
                alternation.append('|');
            }
            alternation.append(regex(patterns.get(i)));
        }
        return Pattern.compile(alternation.append(")$").toString()).matcher(value).matches();
    }

    private static String regex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    /** True when the user explicitly typed a pattern beginning with {@code prefix}. */
    public static boolean namesPrefix(String patternText, String prefix) {
        for (String part : split(patternText)) {
            if (part.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
