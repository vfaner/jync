package com.qqmu.jync.service.version;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads releases from the GitHub REST API using the JDK's built-in HTTP client, following
 * the same timeout discipline as {@code AiChatClient} but allowing redirects, so a
 * repository rename does not permanently break the update check.
 *
 * <p>The API base is derived from the configured repository URL ({@code app.github-url}),
 * so changing the repository does not require a second property. Hosts other than
 * github.com are rejected rather than guessing another vendor's API layout.
 */
@Slf4j
public class GithubReleaseFeed implements ReleaseFeed {

    /** Per-request timeout: how long GitHub has to answer before the chain tries Gitee. */
    static final long DEFAULT_TIMEOUT_MS = 5_000L;

    /** https://github.com/owner/repo(.git)(/) or http variant. */
    private static final Pattern HTTPS_URL =
            Pattern.compile("^https?://github\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?/?$");

    /** git@github.com:owner/repo(.git) */
    private static final Pattern SSH_URL =
            Pattern.compile("^git@github\\.com:([^/]+)/([^/#?:]+?)(?:\\.git)?/?$");

    private final String apiBase;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    @Component
    public static class Bean extends GithubReleaseFeed {
        public Bean(@Value("${app.github-url}") String githubUrl,
                    @Value("${app.update-check.timeout-ms:5000}") long timeoutMs) {
            super(githubUrl, timeoutMs);
        }
    }

    public GithubReleaseFeed(String githubUrl) {
        this(githubUrl, DEFAULT_TIMEOUT_MS);
    }

    public GithubReleaseFeed(String githubUrl, long timeoutMs) {
        String parsed;
        try {
            parsed = toApiBase(githubUrl);
        } catch (IllegalArgumentException e) {
            // A misconfigured repository URL must not stop the application from booting;
            // every check then just fails (and the chained feed moves on to Gitee).
            log.warn("GitHub update check disabled: {}", e.getMessage());
            parsed = null;
        }
        this.apiBase = parsed;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                // NORMAL (not NEVER): GitHub answers renamed repositories with a 301, and the
                // update check must survive the repo being renamed — with NEVER, every deployed
                // instance's check broke the moment the repository moved. NORMAL still refuses
                // HTTPS→HTTP downgrades.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Maps a browser/ssh repository URL to its REST API root.
     *
     * @throws IllegalArgumentException for hosts other than github.com or unparseable URLs
     */
    static String toApiBase(String githubUrl) {
        if (githubUrl == null) {
            throw new IllegalArgumentException("app.github-url is not set");
        }
        String url = githubUrl.trim();
        Matcher m = HTTPS_URL.matcher(url);
        if (!m.matches()) {
            m = SSH_URL.matcher(url);
        }
        if (!m.matches()) {
            throw new IllegalArgumentException("Unsupported repository URL for update check: " + url);
        }
        return "https://api.github.com/repos/" + m.group(1) + "/" + m.group(2);
    }

    @Override
    public Release latest() throws Exception {
        return fetch(apiBase + "/releases/latest");
    }

    @Override
    public Release byTag(String tag) throws Exception {
        return fetch(apiBase + "/releases/tags/"
                + URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private Release fetch(String url) throws Exception {
        if (apiBase == null) {
            throw new IllegalStateException("No GitHub API base derivable from app.github-url");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "jync-update-check")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub API responded " + response.statusCode());
        }
        return parse(response.body());
    }

    /** Parses one GitHub release JSON document; package-private for tests (no sockets). */
    static Release parse(String body) throws Exception {
        JsonNode json = new ObjectMapper().readTree(body);
        String tag = json.path("tag_name").asText(null);
        String htmlUrl = json.path("html_url").asText(null);
        String published = json.path("published_at").asText(null);
        String releaseBody = json.path("body").asText("");
        if (tag == null || htmlUrl == null) {
            throw new IllegalStateException("GitHub API response is missing tag_name/html_url");
        }
        List<Asset> assets = new ArrayList<>();
        for (JsonNode asset : json.path("assets")) {
            String name = asset.path("name").asText(null);
            String downloadUrl = asset.path("browser_download_url").asText(null);
            if (name == null || downloadUrl == null) {
                continue;
            }
            long size = asset.path("size").asLong(0L);
            assets.add(new Asset(name, downloadUrl, size > 0 ? size : null,
                    parseSha256(asset.path("digest").asText(null))));
        }
        Instant publishedAt = null;
        if (published != null && !published.isBlank()) {
            publishedAt = Instant.parse(published);
        }
        return new Release(tag, htmlUrl, publishedAt, releaseBody,
                List.copyOf(assets), Source.GITHUB);
    }

    /**
     * Normalizes an asset {@code digest} to lowercase hex SHA-256, or null when there is none
     * to trust. GitHub reports {@code "sha256:<hex>"}; a different algorithm ({@code md5:…}),
     * a malformed value or an absent field all yield null, so the upgrade knows the bytes could
     * not be verified this way rather than comparing against something bogus.
     */
    private static String parseSha256(String digest) {
        if (digest == null) {
            return null;
        }
        String value = digest.trim();
        if (!value.regionMatches(true, 0, "sha256:", 0, "sha256:".length())) {
            return null;
        }
        value = value.substring("sha256:".length()).trim();
        return value.matches("[0-9a-fA-F]{64}") ? value.toLowerCase(Locale.ROOT) : null;
    }
}
