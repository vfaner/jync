package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Text that carries its own hard-coded color must stay readable in both themes.
 *
 * <p>{@code app.css} states its own invariant: the dark theme only overrides variables, so a
 * component needs no second set of rules — "所有组件无需写第二套深色规则". A rule that paints text
 * with a literal color instead of a variable is therefore the one way that claim can quietly
 * stop being true, and it fails silently: the page renders, the color is simply unreadable
 * against the dark background. {@code .cdc-fail} shipped that way — dark red {@code #b42318}
 * on the dark theme's {@code #0b1120}, about 2.9:1, on the connection test's CDC probe lines
 * (the only place those classes appear).
 *
 * <p>The check computes WCAG contrast for every literal text color against both theme
 * backgrounds and requires 4.5:1, the AA threshold for normal-size text. A rule is exempt when
 * it paints its own background (or its base selector does, for {@code :hover} variants), when
 * it colors an icon ({@code .ico} — decorative palette, reviewed by hand), or when it is listed
 * in {@link #PAINTED_BY_ANCESTOR} because its surface comes from a parent rule.
 */
class DarkThemeTextContrastTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    /** AA for normal text; these are 12.5–13.5px helper lines, not large text. */
    private static final double MIN_TEXT_CONTRAST = 4.5;

    private static final Pattern TEXT_COLOR = Pattern.compile("(?:^|;)\\s*color\\s*:\\s*([^;]+)");
    private static final Pattern BACKGROUND =
            Pattern.compile("(?:^|;)\\s*background(-color)?\\s*:");
    private static final Pattern PSEUDO = Pattern.compile("::?[a-zA-Z-]+(\\([^)]*\\))?");
    private static final Pattern DARK_PREFIX = Pattern.compile("^\\[data-theme=\"dark\"\\]\\s*");

    /**
     * White text on a surface painted by an ancestor. Only rules that paint no surface of their
     * own belong here — the banners set their gradient / {@code background: none} in their own
     * rule, so they are exempted by the background check and need no entry.
     */
    private static final Map<String, String> PAINTED_BY_ANCESTOR = Map.of(
            ".jd-card.jd-msg .jd-body",
            "白色字压在 .jd-card.jd-msg 的黑色半透明底上，底色在父选择器规则里");

    @Test
    @DisplayName("every literal text color reaches 4.5:1 in both themes")
    void everyLiteralTextColorIsReadableInBothThemes() throws Exception {
        List<Rule> rules = parse(Files.readString(CSS, StandardCharsets.UTF_8));
        String lightBg = variable(rules, ":root", "--bg");
        String darkBg = variable(rules, "[data-theme=\"dark\"]", "--bg");
        Map<String, String> darkColors = darkOverrides(rules);
        Set<String> surfaces = selectorsThatPaintABackground(rules);

        Map<String, String> violations = new TreeMap<>();
        Map<String, String> allowedSeen = new TreeMap<>();
        Set<String> evaluated = new TreeSet<>();
        for (Rule rule : rules) {
            // The overrides themselves, and decorative icons, are outside this invariant.
            if (rule.selector.contains("data-theme") || rule.selector.contains(".ico")) {
                continue;
            }
            String color = literalTextColor(rule.body);
            if (color == null
                    || BACKGROUND.matcher(rule.body).find()
                    // A :hover variant inherits its base selector's painted surface.
                    || surfaces.contains(stripPseudoClasses(rule.selector))) {
                continue;
            }
            if (PAINTED_BY_ANCESTOR.containsKey(rule.selector)) {
                allowedSeen.put(rule.selector, PAINTED_BY_ANCESTOR.get(rule.selector));
                continue;
            }
            evaluated.add(rule.selector);

            double light = contrast(color, lightBg);
            double dark = contrast(darkColors.getOrDefault(rule.selector, color), darkBg);
            if (light < MIN_TEXT_CONTRAST || dark < MIN_TEXT_CONTRAST) {
                violations.put(rule.selector, String.format("light %.2f:1, dark %.2f:1", light, dark));
            }
        }

        assertThat(violations)
                .as("these text colors fall below 4.5:1 in one theme (%s on %s / %s on %s); "
                        + "either use a theme variable or add a [data-theme=\"dark\"] override",
                        lightBg, darkBg, darkBg, "dark bg")
                .isEmpty();

        // A scan that evaluates nothing passes trivially; pin that the parser reaches the
        // status helpers this test exists for, and that no allow-list entry has gone stale.
        assertThat(evaluated)
                .as("literal-colored text helpers that must stay readable")
                .contains(".cdc-ok", ".cdc-fail");
        assertThat(PAINTED_BY_ANCESTOR.keySet())
                .as("every allow-list entry must still name a real rule")
                .containsExactlyInAnyOrderElementsOf(allowedSeen.keySet());
    }

    @Test
    @DisplayName("the stylesheet parser sees the rules it reasons about")
    void theScanIsNotVacuous() throws Exception {
        List<Rule> rules = parse(Files.readString(CSS, StandardCharsets.UTF_8));

        assertThat(rules).as("rules parsed from %s", CSS).hasSizeGreaterThan(150);
        assertThat(rules.stream().map(Rule::selector).toList())
                .contains(".cdc-ok", ".cdc-fail", "[data-theme=\"dark\"]", ".btn-primary:hover:not(:disabled)");
        assertThat(variable(rules, ":root", "--bg")).isEqualTo("#ffffff");
        assertThat(variable(rules, "[data-theme=\"dark\"]", "--bg")).isEqualTo("#0b1120");
        // The contrast arithmetic itself: black on white is 21:1, anything equal is 1:1.
        assertThat(contrast("#000000", "#ffffff")).isBetween(20.9, 21.1);
        assertThat(contrast("#b42318", "#0b1120")).isBetween(2.5, 3.3);
        assertThat(contrast("#34d399", "#0b1120")).isGreaterThan(MIN_TEXT_CONTRAST);
    }

    // ─── CSS parsing ───────────────────────────────────────────────────────

    private record Rule(String selector, String body) {
    }

    private static List<Rule> parse(String css) {
        List<Rule> rules = new ArrayList<>();
        collect(css.replaceAll("(?s)/\\*.*?\\*/", ""), rules);
        return rules;
    }

    private static void collect(String css, List<Rule> out) {
        int cursor = 0;
        while (true) {
            int open = css.indexOf('{', cursor);
            if (open < 0) {
                return;
            }
            int close = closingBrace(css, open);
            String prelude = css.substring(cursor, open).trim();
            String body = css.substring(open + 1, close);
            if (prelude.startsWith("@media") || prelude.startsWith("@supports")) {
                collect(body, out);
            } else if (!prelude.startsWith("@")) {
                // @keyframes / @font-face are not style rules we reason about.
                for (String selector : prelude.split(",")) {
                    String normalized = selector.trim().replaceAll("\\s+", " ");
                    if (!normalized.isEmpty()) {
                        out.add(new Rule(normalized, body));
                    }
                }
            }
            cursor = close + 1;
        }
    }

    private static int closingBrace(String css, int open) {
        int depth = 0;
        for (int i = open; i < css.length(); i++) {
            char c = css.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        throw new AssertionError("unbalanced braces in the stylesheet");
    }

    private static String literalTextColor(String body) {
        Matcher m = TEXT_COLOR.matcher(body);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1).trim();
        // var(...) means the theme owns the color; only a literal escapes the variable system.
        return value.startsWith("#") || value.startsWith("rgb") ? value : null;
    }

    private static Set<String> selectorsThatPaintABackground(List<Rule> rules) {
        Set<String> surfaces = new LinkedHashSet<>();
        for (Rule rule : rules) {
            if (BACKGROUND.matcher(rule.body).find()) {
                surfaces.add(rule.selector);
            }
        }
        return surfaces;
    }

    private static Map<String, String> darkOverrides(List<Rule> rules) {
        Map<String, String> overrides = new LinkedHashMap<>();
        for (Rule rule : rules) {
            Matcher prefix = DARK_PREFIX.matcher(rule.selector);
            if (!prefix.find()) {
                continue;
            }
            String color = literalTextColor(rule.body);
            if (color != null) {
                overrides.put(rule.selector.substring(prefix.end()), color);
            }
        }
        return overrides;
    }

    /** {@code .btn-primary:hover:not(:disabled)} → {@code .btn-primary}. */
    private static String stripPseudoClasses(String selector) {
        String stripped = selector;
        String previous;
        do {
            previous = stripped;
            stripped = PSEUDO.matcher(stripped).replaceAll("");
        } while (!stripped.equals(previous));
        return stripped.trim();
    }

    private static String variable(List<Rule> rules, String selector, String name) {
        Pattern declaration = Pattern.compile("(?:^|;)\\s*" + Pattern.quote(name) + "\\s*:\\s*([^;]+)");
        for (Rule rule : rules) {
            if (rule.selector.equals(selector)) {
                Matcher m = declaration.matcher(rule.body);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        }
        throw new AssertionError(name + " is not declared for " + selector);
    }

    // ─── WCAG contrast ─────────────────────────────────────────────────────

    private static double contrast(String foreground, String background) {
        double a = luminance(foreground);
        double b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String color) {
        int[] rgb = rgb(color);
        double r = linear(rgb[0] / 255.0);
        double g = linear(rgb[1] / 255.0);
        double b = linear(rgb[2] / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double linear(double channel) {
        return channel <= 0.04045
                ? channel / 12.92
                : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static int[] rgb(String color) {
        String value = color.trim();
        if (value.startsWith("#")) {
            String hex = value.substring(1);
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
                        + hex.charAt(2) + hex.charAt(2);
            }
            if (hex.length() != 6) {
                throw new AssertionError("unsupported hex color: " + color);
            }
            return new int[]{
                    Integer.parseInt(hex.substring(0, 2), 16),
                    Integer.parseInt(hex.substring(2, 4), 16),
                    Integer.parseInt(hex.substring(4, 6), 16)};
        }
        Matcher m = Pattern.compile("rgba?\\((\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").matcher(value);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))};
        }
        throw new AssertionError("unsupported color: " + color);
    }
}
