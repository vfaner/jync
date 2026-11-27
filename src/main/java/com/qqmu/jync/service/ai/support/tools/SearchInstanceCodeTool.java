package com.qqmu.jync.service.ai.support.tools;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;

import tools.jackson.databind.JsonNode;

/**
 * Read-only search over the deployed Jync artifact: the resources and class constant strings
 * inside jync.jar (or target/classes in a dev run).
 *
 * <p>This is the last evidence layer before the model's own general knowledge. It is not a
 * source-code search: a jar has no .java files. It searches the files a deployment actually
 * carries — application.yml, i18n properties, AI knowledge documents, templates, static JS
 * and the printable constants embedded in com.qqmu.jync class files (error messages, SQL
 * fragments, property names). Scanning is bounded and never touches business data.
 */
@Component
public class SearchInstanceCodeTool implements SupportTool {

    static final int DEFAULT_RESULTS = 15;
    static final int MAX_RESULTS = 50;
    static final long MAX_ENTRY_BYTES = 1_500_000L;
    static final int MAX_ENTRIES = 12_000;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "yml", "yaml", "properties", "md", "txt", "html", "htm", "css", "js", "mjs",
            "json", "xml", "sql", "csv", "svg");

    private final Path location;

    public SearchInstanceCodeTool() {
        this(locateArtifact());
    }

    /** Test seam: a jar or a classes directory to scan instead of the running artifact. */
    SearchInstanceCodeTool(Path location) {
        this.location = location;
    }

    public String name() {
        return "search_instance_code";
    }

    public String description() {
        return "Search the deployed Jync artifact (jync.jar resources and com.qqmu.jync class "
                + "constant strings) for a keyword: application.yml, i18n/ai/templates/static "
                + "files and embedded error messages/SQL/property names. Read-only, bounded, no "
                + "business data. Use it only after search_docs and search_logs, before general "
                + "model knowledge.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "scope", Map.of("type", "string",
                                "enum", List.of("all", "resources", "classes")),
                        "maxResults", Map.of("type", "integer")),
                "required", List.of("query"));
    }

    public Set<String> allowedKeys() {
        return Set.of("query", "scope", "maxResults");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        String query = SupportArgs.requireText(args, "query", 200);
        String scope = SupportArgs.optionalText(args, "scope");
        if (scope == null) {
            scope = "all";
        }
        scope = scope.toLowerCase(Locale.ROOT);
        if (!Set.of("all", "resources", "classes").contains(scope)) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        Long requested = SupportArgs.optionalLong(args, "maxResults");
        int max = requested == null ? DEFAULT_RESULTS
                : (int) Math.max(1, Math.min(MAX_RESULTS, requested));
        List<Map<String, Object>> matches = new ArrayList<>();
        boolean[] truncated = {false};
        if (location == null || !Files.exists(location)) {
            return Map.of("available", false,
                    "reason", "deployed jar/classes directory is not accessible");
        }
        try {
            if (Files.isDirectory(location)) {
                scanDirectory(location, query, scope, max, matches, truncated);
            } else {
                scanJar(location, query, scope, max, matches, truncated);
            }
        } catch (IOException e) {
            return Map.of("error", "error.support.tool.failed");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", location.toString());
        out.put("scope", scope);
        out.put("query", query);
        out.put("truncated", truncated[0]);
        out.put("matches", matches);
        return out;
    }

    private static Path locateArtifact() {
        try {
            return artifactPath(SearchInstanceCodeTool.class.getProtectionDomain()
                    .getCodeSource().getLocation());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Resolves the code-source URL to a readable jar or classes directory. Under
     * {@code java -jar}, Spring Boot wraps the location as
     * {@code jar:file:/path/jync.jar!/BOOT-INF/classes!/} (or the 3.2+ nested
     * {@code jar:nested:/path/jync.jar/!BOOT-INF/classes!/}), which is not a NIO file URI:
     * {@code Path.of} rejects it. Strip the wrapper and open the executable jar itself —
     * {@link #scanJar} knows the {@code BOOT-INF/classes} layout.
     */
    static Path artifactPath(URL location) {
        if (location == null) {
            return null;
        }
        Path artifact = artifactPath(location.toString());
        if (artifact != null) {
            return artifact;
        }
        try {
            return Path.of(location.toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** Same resolution over the URL text, independent of the JDK's URL protocol handlers. */
    static Path artifactPath(String text) {
        if (text == null || !text.startsWith("jar:")) {
            return null;
        }
        Path nested = nestedJarPath(text);
        if (nested != null && Files.isRegularFile(nested)) {
            return nested;
        }
        int separator = text.indexOf("!/");
        if (separator > 4) {
            Path jar = pathOrNull(text.substring(4, separator));
            if (jar != null && Files.isRegularFile(jar)) {
                return jar;
            }
        }
        return null;
    }

    /**
     * Spring Boot 3.2+ uses {@code jar:nested:<file-url>/!<entry>!/}. The outer file URL ends
     * before the first {@code /!}; a plain {@code jar:file:...!/entry!} ends before {@code !/}.
     */
    private static Path nestedJarPath(String text) {
        String inner = text.substring(4);
        if (!inner.startsWith("nested:")) {
            return null;
        }
        String nested = inner.substring("nested:".length());
        int separator = nested.indexOf("/!");
        if (separator <= 0) {
            return null;
        }
        return pathOrNull(nested.substring(0, separator));
    }

    private static Path pathOrNull(String value) {
        try {
            return Path.of(URI.create(value));
        } catch (RuntimeException ignored) {
            // A nested URL can carry a raw path rather than a file: URI.
            try {
                return Path.of(value);
            } catch (RuntimeException notAPath) {
                return null;
            }
        }
    }

    private void scanJar(Path jar, String query, String scope, int max,
                         List<Map<String, Object>> matches, boolean[] truncated)
            throws IOException {
        byte[] needle = query.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        int scanned = 0;
        try (JarFile file = new JarFile(jar.toFile())) {
            // Spring Boot's executable jar keeps application resources and classes under
            // BOOT-INF/classes. In that layout ignore everything outside it (loader classes
            // and BOOT-INF/lib dependencies are not this instance's code).
            boolean bootLayout = file.getEntry("BOOT-INF/classes/") != null
                    || file.getEntry("BOOT-INF/classes/application.yml") != null
                    || file.getEntry("BOOT-INF/classes/application.yaml") != null;
            if (!bootLayout) {
                // Config can be external-only, so the two well-known entries may be absent
                // even though the product classes still live under BOOT-INF/classes.
                Enumeration<JarEntry> probe = file.entries();
                while (probe.hasMoreElements()) {
                    if (probe.nextElement().getName().startsWith("BOOT-INF/classes/")) {
                        bootLayout = true;
                        break;
                    }
                }
            }
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String rawName = entry.getName();
                if (bootLayout && !rawName.startsWith("BOOT-INF/classes/")) {
                    continue;
                }
                String name = bootLayout
                        ? rawName.substring("BOOT-INF/classes/".length())
                        : rawName;
                boolean resource = isResource(name);
                boolean clazz = isProductClass(name);
                if (("resources".equals(scope) && !resource)
                        || ("classes".equals(scope) && !clazz)
                        || (!resource && !clazz)) {
                    continue;
                }
                long size = entry.getSize();
                if (size > MAX_ENTRY_BYTES || ++scanned > MAX_ENTRIES) {
                    truncated[0] = true;
                    if (scanned > MAX_ENTRIES) {
                        break;
                    }
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    byte[] bytes = in.readNBytes((int) (size >= 0 ? size : MAX_ENTRY_BYTES));
                    if (resource) {
                        scanResource(name, bytes, query, max, matches, truncated);
                    } else {
                        scanClass(name, bytes, needle, max, matches, truncated);
                    }
                }
                if (matches.size() >= max) {
                    truncated[0] = true;
                    break;
                }
            }
        }
    }

    private void scanDirectory(Path root, String query, String scope, int max,
                               List<Map<String, Object>> matches, boolean[] truncated)
            throws IOException {
        byte[] needle = query.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).limit(MAX_ENTRIES).toList();
            for (Path path : files) {
                String name = root.relativize(path).toString().replace('\\', '/');
                boolean resource = isResource(name);
                boolean clazz = isProductClass(name);
                if (("resources".equals(scope) && !resource)
                        || ("classes".equals(scope) && !clazz)
                        || (!resource && !clazz)) {
                    continue;
                }
                if (Files.size(path) > MAX_ENTRY_BYTES) {
                    truncated[0] = true;
                    continue;
                }
                byte[] bytes = Files.readAllBytes(path);
                if (resource) {
                    scanResource(name, bytes, query, max, matches, truncated);
                } else {
                    scanClass(name, bytes, needle, max, matches, truncated);
                }
                if (matches.size() >= max) {
                    truncated[0] = true;
                    break;
                }
            }
        }
    }

    private static void scanResource(String name, byte[] bytes, String query, int max,
                                     List<Map<String, Object>> matches, boolean[] truncated) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String needle = query;
        int from = 0;
        while (matches.size() < max) {
            // Search the original text case-insensitively instead of indexing a lowercased
            // copy: some characters (U+0130, for example) change length when lowercased,
            // which made the offset point past the end of the original string.
            int hit = indexOfIgnoreCase(text, needle, from);
            if (hit < 0) {
                return;
            }
            int line = 1;
            for (int i = 0; i < hit; i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            int start = Math.max(0, text.lastIndexOf('\n', hit) + 1);
            int end = text.indexOf('\n', hit);
            if (end < 0) {
                end = text.length();
            }
            // Center the excerpt on the hit. Taking the first 600 characters of the line
            // dropped the keyword entirely on minified JS/CSS and long SQL lines.
            int snippetStart = Math.max(start, hit - 200);
            int snippetEnd = Math.min(end, snippetStart + 600);
            if (snippetEnd < hit + needle.length()) {
                snippetEnd = Math.min(end, hit + needle.length() + 200);
                snippetStart = Math.max(start, snippetEnd - 600);
            }
            matches.add(match(name, "resource", line,
                    ToolText.safe(text.substring(snippetStart, snippetEnd), 600)));
            from = hit + needle.length();
            if (from <= hit) {
                return;
            }
        }
        truncated[0] = true;
    }

    private static void scanClass(String name, byte[] bytes, byte[] needle, int max,
                                  List<Map<String, Object>> matches, boolean[] truncated) {
        int from = 0;
        while (matches.size() < max) {
            int hit = indexOfIgnoreCase(bytes, needle, from);
            if (hit < 0) {
                return;
            }
            String snippet = printableAround(bytes, hit, needle.length);
            if (snippet != null && !snippet.isBlank()) {
                matches.add(match(name, "class", null, ToolText.safe(snippet, 600)));
            }
            from = hit + Math.max(1, needle.length);
        }
    }

    private static Map<String, Object> match(String path, String kind, Integer line,
                                             String snippet) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("kind", kind);
        row.put("line", line);
        row.put("snippet", snippet);
        return row;
    }

    private static boolean isResource(String name) {
        if (!isTextPath(name)) {
            return false;
        }
        return name.equals("application.yml") || name.equals("application.yaml")
                || name.startsWith("i18n/") || name.startsWith("ai/")
                || name.startsWith("templates/") || name.startsWith("static/")
                || name.startsWith("docs/")
                || (name.endsWith(".properties") && name.startsWith("com/qqmu/jync/"));
    }

    /** Static assets can include fonts and images; scanning those only yields byte noise. */
    private static boolean isTextPath(String name) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        if (dot <= slash + 1 || dot == name.length() - 1) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isProductClass(String name) {
        return name.startsWith("com/qqmu/jync/") && name.endsWith(".class");
    }

    /** Case-insensitive search over decoded text, preserving offsets in the original string. */
    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        if (needle.isEmpty()) {
            return -1;
        }
        int start = Math.max(0, from);
        int last = haystack.length() - needle.length();
        for (int i = start; i <= last; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /** Case-insensitive byte search; UTF-8 constants from ASCII and CJK both match. */
    private static int indexOfIgnoreCase(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            boolean hit = true;
            for (int j = 0; j < needle.length; j++) {
                if (lower(haystack[i + j]) != needle[j]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return i;
            }
        }
        return -1;
    }

    private static byte lower(byte value) {
        return value >= 'A' && value <= 'Z' ? (byte) (value + ('a' - 'A')) : value;
    }

    /** The printable run containing a byte match, decoded as UTF-8 where possible. */
    private static String printableAround(byte[] bytes, int hit, int length) {
        int start = hit;
        while (start > 0 && printable(bytes[start - 1])) {
            start--;
        }
        int end = hit + length;
        while (end < bytes.length && printable(bytes[end])) {
            end++;
        }
        if (end - start < 4) {
            return null;
        }
        return new String(bytes, start, end - start, StandardCharsets.UTF_8)
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", "");
    }

    private static boolean printable(byte value) {
        int b = value & 0xFF;
        return b >= 0x20 && b != 0x7F;
    }
}
