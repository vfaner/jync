package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SearchInstanceCodeToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));

    @Test
    void findsResourceLinesAndClassConstantStrings(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("jync.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "ai/test.md", "intro\nNEEDLE_TOKEN appears in the knowledge file\n");
            ByteArrayOutputStream clazz = new ByteArrayOutputStream();
            clazz.write(new byte[]{0, 1, 2, 3});
            clazz.write("com/qqmu/jync/Fake.class NEEDLE_TOKEN in a class constant"
                    .getBytes(StandardCharsets.UTF_8));
            put(out, "com/qqmu/jync/Fake.class", clazz.toByteArray());
            put(out, "com/qqmu/jync/Other.class", "nothing here".getBytes(StandardCharsets.UTF_8));
        }
        SearchInstanceCodeTool tool = new SearchInstanceCodeTool(jar);

        ObjectNode args = mapper.createObjectNode().put("query", "NEEDLE_TOKEN");
        var result = mapper.readTree(mapper.writeValueAsString(tool.execute(args, ctx)));

        assertThat(result.path("matches")).hasSize(2);
        assertThat(result.path("matches").get(0).path("kind").asString()).isEqualTo("resource");
        assertThat(result.path("matches").get(0).path("line").asInt()).isEqualTo(2);
        assertThat(result.path("matches").get(1).path("kind").asString()).isEqualTo("class");
        assertThat(result.path("matches").get(1).path("snippet").asString())
                .contains("NEEDLE_TOKEN");
    }

    @Test
    void scopeCanRestrictTheScan(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("jync.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "application.yml", "jync: NEEDLE_TOKEN\n");
            put(out, "com/qqmu/jync/Fake.class", "NEEDLE_TOKEN".getBytes(StandardCharsets.UTF_8));
        }
        SearchInstanceCodeTool tool = new SearchInstanceCodeTool(jar);

        ObjectNode args = mapper.createObjectNode().put("query", "NEEDLE_TOKEN")
                .put("scope", "resources");
        var result = mapper.readTree(mapper.writeValueAsString(tool.execute(args, ctx)));

        assertThat(result.path("matches")).hasSize(1);
        assertThat(result.path("matches").get(0).path("path").asString())
                .isEqualTo("application.yml");
    }

    @Test
    void aSpringBootExecutableJarIsScannedThroughItsBootInfClassesLayout(@TempDir Path dir)
            throws Exception {
        Path jar = dir.resolve("jync.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "BOOT-INF/classes/application.yml", "jync: NEEDLE_TOKEN\n");
            put(out, "BOOT-INF/classes/com/qqmu/jync/Fake.class",
                    "NEEDLE_TOKEN in a product class".getBytes(StandardCharsets.UTF_8));
            // Loader classes and dependency jars are not this instance's code.
            put(out, "com/qqmu/jync/Decoy.class",
                    "NEEDLE_TOKEN in a decoy".getBytes(StandardCharsets.UTF_8));
            put(out, "BOOT-INF/lib/spring.jar",
                    "NEEDLE_TOKEN in a dependency".getBytes(StandardCharsets.UTF_8));
        }
        SearchInstanceCodeTool tool = new SearchInstanceCodeTool(jar);

        ObjectNode args = mapper.createObjectNode().put("query", "NEEDLE_TOKEN");
        var result = mapper.readTree(mapper.writeValueAsString(tool.execute(args, ctx)));

        assertThat(result.path("matches")).hasSize(2);
        assertThat(result.path("matches").get(0).path("path").asString())
                .isEqualTo("application.yml");
        assertThat(result.path("matches").get(1).path("path").asString())
                .isEqualTo("com/qqmu/jync/Fake.class");
        assertThat(result.path("truncated").asBoolean()).isFalse();
    }

    @Test
    void aLongMinifiedLineKeepsTheMatchedKeywordInTheExcerpt(@TempDir Path dir)
            throws Exception {
        Path jar = dir.resolve("jync.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "static/app.js", "x".repeat(1200) + "NEEDLE_TOKEN"
                    + "y".repeat(1200) + "\n");
        }
        SearchInstanceCodeTool tool = new SearchInstanceCodeTool(jar);

        ObjectNode args = mapper.createObjectNode().put("query", "NEEDLE_TOKEN");
        var result = mapper.readTree(mapper.writeValueAsString(tool.execute(args, ctx)));

        assertThat(result.path("matches")).hasSize(1);
        assertThat(result.path("matches").get(0).path("snippet").asString())
                .contains("NEEDLE_TOKEN");
    }

    @Test
    void aLowercaseExpandingCharacterDoesNotBreakTheResourceSnippet(@TempDir Path dir)
            throws Exception {
        Path jar = dir.resolve("jync.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            // U+0130 lowercases to two code points; indexing the lowercased copy used to
            // point past the end of the original text.
            put(out, "ai/turkish.md", "İ".repeat(50) + "NEEDLE_TOKEN");
        }
        SearchInstanceCodeTool tool = new SearchInstanceCodeTool(jar);

        ObjectNode args = mapper.createObjectNode().put("query", "NEEDLE_TOKEN");
        var result = mapper.readTree(mapper.writeValueAsString(tool.execute(args, ctx)));

        assertThat(result.path("matches")).hasSize(1);
        assertThat(result.path("matches").get(0).path("snippet").asString())
                .contains("NEEDLE_TOKEN");
    }

    @Test
    void binaryStaticAssetsAreNotScannedAsText(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("jync.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "static/logo.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            put(out, "application.yml", "jync: NEEDLE_TOKEN\n");
        }
        SearchInstanceCodeTool tool = new SearchInstanceCodeTool(jar);

        ObjectNode args = mapper.createObjectNode().put("query", "NEEDLE_TOKEN");
        var result = mapper.readTree(mapper.writeValueAsString(tool.execute(args, ctx)));

        assertThat(result.path("matches")).hasSize(1);
        assertThat(result.path("matches").get(0).path("path").asString())
                .isEqualTo("application.yml");
    }

    @Test
    void aClassicSpringBootJarUrlResolvesToTheExecutableJar(@TempDir Path dir)
            throws Exception {
        Path jar = Files.createFile(dir.resolve("jync.jar"));

        URI nested = URI.create("jar:" + jar.toUri() + "!/BOOT-INF/classes!/");

        assertThat(SearchInstanceCodeTool.artifactPath(nested.toURL())).isEqualTo(jar);
    }

    @Test
    void aNestedSpringBootJarUrlResolvesToTheExecutableJar(@TempDir Path dir)
            throws Exception {
        Path jar = Files.createFile(dir.resolve("jync.jar"));

        // Spring Boot 3.2+ / 4.x format seen in stack traces:
        // jar:nested:/path/jync.jar/!BOOT-INF/classes!/com/...
        String nested = "jar:nested:" + jar.toUri() + "/!BOOT-INF/classes!/";

        assertThat(SearchInstanceCodeTool.artifactPath(nested)).isEqualTo(jar);
    }

    @Test
    void aNestedUrlWithARawFilesystemPathAlsoResolves(@TempDir Path dir) throws Exception {
        Path jar = Files.createFile(dir.resolve("jync.jar"));

        String nested = "jar:nested:" + jar.toAbsolutePath() + "/!BOOT-INF/classes!/";

        assertThat(SearchInstanceCodeTool.artifactPath(nested)).isEqualTo(jar);
    }

    private static void put(JarOutputStream out, String name, String text) throws Exception {
        put(out, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
