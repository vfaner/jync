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
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The container image must ship the running version's release notes.
 *
 * <p>{@code maven-resources-plugin} bundles one markdown file into the jar so the settings page
 * can show "what's new in this version" on an offline intranet host. When that file is missing
 * the copy step logs a skipped resource directory and the build still succeeds — so the jar
 * boots, the page renders, and only the release-notes card is silently empty. v1.8.1 shipped a
 * container image built that way: commit 0f37474 moved the notes from the repo root into
 * {@code docs/release-notes/} and updated the POM, but the Docker build context still excluded
 * {@code docs} and copied bare {@code pom.xml} + {@code src}.
 *
 * <p>The three files have to agree, and nothing else checks that: the POM names the directory,
 * the Dockerfile must copy it, and {@code .dockerignore} must not prune it from the context.
 */
class ContainerBuildReleaseNotesTest {

    /** Surefire runs with the project directory as the working directory. */
    private static final Path ROOT = Path.of("");

    private static final String POM = "pom.xml";
    private static final String DOCKERFILE = "Dockerfile";
    private static final String DOCKERIGNORE = ".dockerignore";

    private static final Pattern COPY = Pattern.compile("(?m)^\\s*COPY\\s+(.+)$");

    @Test
    @DisplayName("the Dockerfile copies the directory the POM bundles notes from")
    void dockerfileCopiesTheReleaseNotesDirectory() throws Exception {
        String notesDir = pomReleaseNotesDirectory();

        assertThat(copySources(read(DOCKERFILE)))
                .as("COPY sources in %s; the build stage must receive %s or the jar has no "
                        + "release-notes/ entry", DOCKERFILE, notesDir)
                .contains(notesDir);
    }

    @Test
    @DisplayName(".dockerignore keeps that directory in the build context")
    void dockerignoreKeepsReleaseNotesInTheBuildContext() throws Exception {
        // A concrete file rather than the bare directory: a "docs" rule excludes everything
        // below it, and only a later re-include brings the notes back.
        String probe = pomReleaseNotesDirectory() + "/" + newestNotesFile();
        List<String> rules = ignoreRules(read(DOCKERIGNORE));

        assertThat(inBuildContext(rules, probe))
                .as("%s excludes %s, so COPY cannot see it; rules were %s",
                        DOCKERIGNORE, probe, rules)
                .isTrue();
    }

    @Test
    @DisplayName("the directory the POM and Dockerfile name actually holds a notes file")
    void theNotesDirectoryHoldsANotesFile() throws Exception {
        String notesDir = pomReleaseNotesDirectory();

        assertThat(Files.isDirectory(ROOT.resolve(notesDir)))
                .as("%s exists", notesDir)
                .isTrue();
        assertThat(newestNotesFile()).matches("RELEASE_NOTES_v\\d+\\.\\d+\\.\\d+\\.md");
    }

    /**
     * Guards the checks above: an evaluator that always answers "included" would pass them no
     * matter what {@code .dockerignore} said.
     */
    @Test
    @DisplayName("the context check can actually report an excluded path")
    void theContextCheckCanReportAnExcludedPath() {
        String probe = "docs/release-notes/RELEASE_NOTES_v1.0.0.md";

        assertThat(inBuildContext(List.of("docs"), probe)).isFalse();
        assertThat(inBuildContext(List.of("docs", "!docs/release-notes"), probe)).isTrue();
        // Last matching rule wins, exactly as in Docker.
        assertThat(inBuildContext(List.of("!docs/release-notes", "docs"), probe)).isFalse();
    }

    /** The {@code <directory>} the POM's copy-release-notes execution reads, repo-relative. */
    private static String pomReleaseNotesDirectory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        Document pom = factory.newDocumentBuilder().parse(ROOT.resolve(POM).toFile());

        NodeList plugins = pom.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if (!"maven-resources-plugin".equals(text(plugin, "artifactId"))) {
                continue;
            }
            NodeList directories = plugin.getElementsByTagName("directory");
            for (int j = 0; j < directories.getLength(); j++) {
                String value = directories.item(j).getTextContent().trim();
                String stripped = value.replace("${project.basedir}/", "");
                if (stripped.startsWith("docs/")) {
                    return stripped;
                }
            }
        }
        throw new AssertionError("no release-notes <directory> in the POM's maven-resources-plugin");
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    /** The lexicographically last notes file; the exact version is irrelevant to packaging. */
    private static String newestNotesFile() throws Exception {
        String notesDir = pomReleaseNotesDirectory();
        try (Stream<Path> files = Files.list(ROOT.resolve(notesDir))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("RELEASE_NOTES_v") && name.endsWith(".md"))
                    .max(String::compareTo)
                    .orElseThrow(() -> new AssertionError("no RELEASE_NOTES_v*.md in " + notesDir));
        }
    }

    /** {@code COPY} sources, skipping multi-stage {@code --from=} copies (not context paths). */
    private static List<String> copySources(String dockerfile) {
        List<String> sources = new ArrayList<>();
        Matcher m = COPY.matcher(dockerfile);
        while (m.find()) {
            List<String> tokens = new ArrayList<>(List.of(m.group(1).trim().split("\\s+")));
            if (tokens.get(0).startsWith("--from=")) {
                continue;
            }
            tokens.removeIf(t -> t.startsWith("--"));
            // Every token but the destination is a source.
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

    /**
     * Whether one repo-relative path survives {@code .dockerignore}, last matching rule winning.
     *
     * <p>Supports the two shapes this file uses: a directory prefix ({"{@code docs}" excludes
     * everything under it) and a glob. Globs are matched with the JDK's path matcher, so a
     * pattern with {@code *} does not cross {@code /}, same as Docker.
     */
    private static boolean inBuildContext(List<String> rules, String path) {
        boolean included = true;
        for (String rule : rules) {
            boolean negated = rule.startsWith("!");
            String pattern = negated ? rule.substring(1) : rule;
            if (matches(pattern, path)) {
                included = negated;
            }
        }
        return included;
    }

    private static boolean matches(String pattern, String path) {
        String dir = pattern.endsWith("/") ? pattern.substring(0, pattern.length() - 1) : pattern;
        if (path.equals(dir) || path.startsWith(dir + "/")) {
            return true;
        }
        Path candidate = Path.of(path);
        PathMatcher glob = candidate.getFileSystem().getPathMatcher("glob:" + pattern);
        return glob.matches(candidate);
    }

    private static String read(String file) throws Exception {
        return Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8);
    }
}
