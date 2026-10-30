package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every message key written in server code resolves to a sentence.
 *
 * <p>A controller puts a key on a flash attribute and the layout renders it through the message
 * source, so a key nobody defined shows the user {@code ??error.project.not.found_zh_CN??} — on
 * one page, in one language, until somebody reports it. {@link I18nBundleParityTest} compares the
 * two bundles against each other; it cannot see a key that exists in neither.
 *
 * <p>The scan is deliberately narrow: string literals whose first segment is a namespace the
 * bundles actually use ({@code error}, {@code msg}, {@code oa}, …). That keeps it off every other
 * dotted literal in the codebase — {@code java.lang.String}, SQL fragments, file names.
 */
class JavaKeyLiteralsExistInBothBundlesTest {

    /**
     * Keys that reach the browser as sentinels and are localised by {@code app.js}, not by the
     * message source. Their label lives in the page's {@code data-*} attributes; listing them
     * here is the decision that they are protocol strings, so a new one has to be listed too.
     */
    private static final Set<String> BROWSER_RESOLVED = Set.of(
            "error.db.health.timeout",
            "error.db.health.invalid");

    private static final Pattern STRING_LITERAL =
            Pattern.compile("\"([a-z][A-Za-z0-9]*(?:\\.[a-z0-9][A-Za-z0-9_-]*)+)\"");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

    @Test
    @DisplayName("every key literal in main/java exists in both bundles")
    void everyKeyLiteralExistsInBothBundles() throws Exception {
        Set<String> en = I18nBundleParityTest.keysOf("i18n/messages.properties");
        Set<String> zh = I18nBundleParityTest.keysOf("i18n/messages_zh_CN.properties");
        Set<String> namespaces = namespacesOf(en);

        TreeMap<String, String> problems = new TreeMap<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (String key : keyLiteralsIn(source, namespaces)) {
                    if (BROWSER_RESOLVED.contains(key)) {
                        continue;
                    }
                    if (!en.contains(key)) {
                        problems.putIfAbsent(key,
                                "used in " + file + " but missing from messages.properties");
                    } else if (!zh.contains(key)) {
                        problems.putIfAbsent(key,
                                "used in " + file + " but missing from messages_zh_CN.properties");
                    }
                }
            }
        }

        assertThat(problems).as("a key with no bundle entry renders as ??key_zh_CN??").isEmpty();
    }

    /** A scan that cannot fail is not a guard: prove it both reports and ignores. */
    @Test
    @DisplayName("the scan reports a key with no entry and ignores commented-out ones")
    void theScanCanFail() {
        Set<String> namespaces = Set.of("error", "msg");
        Set<String> bundleKeys = Set.of("error.real.key");
        String source = "flash.addFlashAttribute(\"error\", \"error.typo.key\");\n"
                + "/** \"error.in.javadoc\" is documentation, not a lookup. */\n"
                + "msg(\"msg.real.but.absent\");\n"
                + "String other = \"java.lang.String\";\n";

        Set<String> found = new TreeSet<>(keyLiteralsIn(stripComments(source), namespaces));
        found.removeAll(BROWSER_RESOLVED);

        assertThat(found).containsExactly("error.typo.key", "msg.real.but.absent");
        assertThat(found).doesNotContain("error.in.javadoc", "java.lang.String");
        assertThat(bundleKeys).doesNotContainAnyElementsOf(found);
    }

    /** Top-level namespaces the bundles use, e.g. {@code error} from {@code error.project.not.found}. */
    private static Set<String> namespacesOf(Set<String> keys) {
        Set<String> namespaces = new TreeSet<>();
        for (String key : keys) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                namespaces.add(key.substring(0, dot));
            }
        }
        return namespaces;
    }

    /** Drops block comments so a key named in prose is not mistaken for a lookup. */
    private static String stripComments(String source) {
        return BLOCK_COMMENT.matcher(source).replaceAll("");
    }

    /** Literals in {@code source} that are shaped like a key in one of {@code namespaces}. */
    private static List<String> keyLiteralsIn(String source, Set<String> namespaces) {
        List<String> keys = new java.util.ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(source);
        while (matcher.find()) {
            String literal = matcher.group(1);
            if (namespaces.contains(literal.substring(0, literal.indexOf('.')))) {
                keys.add(literal);
            }
        }
        return keys;
    }
}
