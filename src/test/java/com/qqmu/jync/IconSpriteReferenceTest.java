package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Every {@code <svg class="ico"><use href="#name"/></svg>} in a template names a symbol that
 * {@code fragments/icons.html} actually defines.
 *
 * <p>Thymeleaf substitutes nothing when a sprite id is missing: the {@code <use>} simply points at
 * an element that does not exist, the SVG keeps its box and paints nothing, and the button renders
 * with an empty gap where the icon should be. No exception, no log line, and the page still passes
 * every other test — v1.8.1 shipped the bulk-plan page's「导出 CSV」button that way.
 */
class IconSpriteReferenceTest {

    private static final String SPRITE = "templates/fragments/icons.html";
    private static final Pattern USE = Pattern.compile("<use\\s[^>]*?href=\"#([^\"]+)\"");
    private static final Pattern SYMBOL = Pattern.compile("<symbol\\b[^>]*?\\bid=\"([^\"]+)\"");
    /** Comments mention {@code <use href="#name">} as prose; scanning them would invent uses. */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    @Test
    @DisplayName("every <use href=\"#…\"> resolves to a defined symbol")
    void everyIconReferenceResolves() throws Exception {
        Set<String> defined = symbolIds();
        Map<String, Set<String>> usedBy = referencesByTemplate();

        Map<String, Set<String>> unresolved = new TreeMap<>();
        usedBy.forEach((template, ids) -> {
            Set<String> missing = new TreeSet<>(ids);
            missing.removeAll(defined);
            if (!missing.isEmpty()) {
                unresolved.put(template, missing);
            }
        });

        assertThat(unresolved)
                .as("these <use href=\"#…\"> ids have no <symbol id=\"…\"> in %s, so the icon renders "
                        + "as an empty box: %s", SPRITE, defined)
                .isEmpty();
    }

    /**
     * A scan that finds nothing passes trivially; a typo in the pattern, a moved templates
     * directory, or an empty sprite would all look like success. This pins that both sides of the
     * comparison are populated.
     */
    @Test
    @DisplayName("the scan actually reads the sprite and the templates")
    void theScanIsNotVacuous() throws Exception {
        assertThat(symbolIds()).as("symbols parsed from %s", SPRITE).contains("check-lg");

        Map<String, Set<String>> usedBy = referencesByTemplate();
        assertThat(usedBy).as("templates with at least one icon reference").isNotEmpty();
        assertThat(usedBy.values().stream().flatMap(Set::stream))
                .as("icon ids referenced across all templates")
                .contains("check-lg");
        // Fragments are th:replace'd into every list page, so an icon missing there breaks
        // dozens of pages; a top-level-only scan would report clean.
        assertThat(usedBy).as("fragments under templates/ are scanned too")
                .containsKey("pager.html");
    }

    private static Set<String> symbolIds() throws Exception {
        return matches(read(SPRITE), SYMBOL);
    }

    /** Template resource path → the icon ids it references, in file order. */
    private static Map<String, Set<String>> referencesByTemplate() throws Exception {
        Map<String, Set<String>> usedBy = new TreeMap<>();
        Resource[] templates = new PathMatchingResourcePatternResolver()
                // ** covers templates/*.html and templates/fragments/*.html alike.
                .getResources("classpath:templates/**/*.html");
        for (Resource template : templates) {
            String body = COMMENT.matcher(new String(template.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8)).replaceAll("");
            Set<String> ids = matches(body, USE);
            if (!ids.isEmpty()) {
                usedBy.put(template.getFilename(), ids);
            }
        }
        return usedBy;
    }

    private static Set<String> matches(String body, Pattern pattern) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = pattern.matcher(body);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    private static String read(String classpathResource) throws Exception {
        return new String(new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + classpathResource).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
