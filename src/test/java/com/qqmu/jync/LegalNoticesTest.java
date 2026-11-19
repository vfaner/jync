package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Apache-2.0 requires the license text and the NOTICE to travel with every distribution.
 *
 * <p>The jar is the product: {@code maven-resources-plugin} copies both files into
 * {@code META-INF/}, and the Docker build stage must receive them from the context or the copy
 * step silently ships nothing. These checks pin the three files that have to agree.
 */
class LegalNoticesTest {

    /** Surefire runs with the project directory as the working directory. */
    private static final Path ROOT = Path.of("");

    private static final Pattern COPY = Pattern.compile("(?m)^\\s*COPY\\s+(.+)$");

    @Test
    @DisplayName("the Docker build stage receives LICENSE and NOTICE")
    void dockerfileCopiesTheLegalFiles() throws Exception {
        assertThat(copySources(read("Dockerfile")))
                .as("COPY sources in Dockerfile")
                .contains("LICENSE", "NOTICE");
    }

    @Test
    @DisplayName(".dockerignore keeps the legal files in the build context")
    void dockerignoreKeepsTheLegalFiles() throws Exception {
        List<String> rules = ignoreRules(read(".dockerignore"));

        assertThat(inBuildContext(rules, "LICENSE")).as("LICENSE under %s", rules).isTrue();
        assertThat(inBuildContext(rules, "NOTICE")).as("NOTICE under %s", rules).isTrue();
    }

    @Test
    @DisplayName("the POM bundles both files into META-INF")
    void pomCopiesTheLegalFilesIntoTheJar() throws Exception {
        String pom = read("pom.xml");

        assertThat(pom).contains("<id>copy-legal</id>");
        assertThat(pom).contains("${project.build.outputDirectory}/META-INF");
        assertThat(pom).contains("<include>LICENSE</include>", "<include>NOTICE</include>");
    }

    @Test
    @DisplayName("LICENSE is the Apache text and NOTICE names the footer attribution")
    void theFilesSayWhatTheDistributionRequires() throws Exception {
        assertThat(read("LICENSE").stripLeading()).startsWith("Apache License");
        assertThat(read("NOTICE")).contains("jync.qqmu.com", "Copyright 2026");
    }

    private static List<String> copySources(String dockerfile) {
        List<String> sources = new ArrayList<>();
        Matcher m = COPY.matcher(dockerfile);
        while (m.find()) {
            List<String> tokens = new ArrayList<>(List.of(m.group(1).trim().split("\\s+")));
            if (tokens.get(0).startsWith("--from=")) {
                continue;
            }
            tokens.removeIf(t -> t.startsWith("--"));
            sources.addAll(tokens.subList(0, tokens.size() - 1));
        }
        return sources;
    }

    private static List<String> ignoreRules(String dockerignore) {
        List<String> rules = new ArrayList<>();
        for (String line : dockerignore.split("\\R")) {
            String rule = line.trim();
            if (!rule.isEmpty() && !rule.startsWith("#")) {
                rules.add(rule);
            }
        }
        return rules;
    }

    /** Last matching rule wins, as in Docker. */
    private static boolean inBuildContext(List<String> rules, String path) {
        boolean included = true;
        for (String rule : rules) {
            boolean negated = rule.startsWith("!");
            String pattern = negated ? rule.substring(1) : rule;
            String dir = pattern.endsWith("/") ? pattern.substring(0, pattern.length() - 1) : pattern;
            boolean matches = path.equals(dir) || path.startsWith(dir + "/");
            if (!matches) {
                Path candidate = Path.of(path);
                PathMatcher glob = candidate.getFileSystem().getPathMatcher("glob:" + pattern);
                matches = glob.matches(candidate);
            }
            if (matches) {
                included = negated;
            }
        }
        return included;
    }

    private static String read(String file) throws Exception {
        return Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8);
    }
}
