package com.qqmu.jync.service.version;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads releases from Gitee's v5 REST API — the domestic mirror the update check falls back
 * to whenever GitHub does not answer within its timeout.
 *
 * <p>Differences from the GitHub payload: Gitee has no {@code html_url} (the browser URL is
 * derived), timestamps arrive as {@code created_at} with a local offset (not UTC ISO), and
 * assets carry no byte size. Asset download URLs are stable:
 * {@code https://gitee.com/{owner}/{repo}/releases/download/{tag}/{name}}.
 */
@Slf4j
public class GiteeReleaseFeed implements ReleaseFeed {

    static final long DEFAULT_TIMEOUT_MS = 5_000L;

    /** https://gitee.com/owner/repo(.git)(/) or http variant. */
    private static final Pattern HTTPS_URL =
            Pattern.compile("^https?://gitee\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?/?$");

    private final String owner;
    private final String repo;
    private final String apiBase;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    @Component
    public static class Bean extends GiteeReleaseFeed {
        public Bean(@Value("${app.gitee-url:}") String giteeUrl,
                    @Value("${app.update-check.timeout-ms:5000}") long timeoutMs) {
            super(giteeUrl, timeoutMs);
        }
    }

    public GiteeReleaseFeed(String giteeUrl) {
        this(giteeUrl, DEFAULT_TIMEOUT_MS);
    }

    public GiteeReleaseFeed(String giteeUrl, long timeoutMs) {
        String parsedOwner = null;
        String parsedRepo = null;
        try {
            Matcher m = HTTPS_URL.matcher(giteeUrl == null ? "" : giteeUrl.trim());
            if (!m.matches()) {
                throw new IllegalArgumentException(
                        "Unsupported repository URL for Gitee update check: " + giteeUrl);
            }
            parsedOwner = m.group(1);
            parsedRepo = m.group(2);
        } catch (IllegalArgumentException e) {
            // Same contract as the GitHub feed: a bad URL degrades checks, never boot.
            log.warn("Gitee update check disabled: {}", e.getMessage());
        }
        this.owner = parsedOwner;
        this.repo = parsedRepo;
        this.apiBase = parsedOwner == null ? null
                : "https://gitee.com/api/v5/repos/" + parsedOwner + "/" + parsedRepo;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                // Attachment downloads 302 onto Gitee's object storage; API redirects on
                // renames must survive too. NORMAL still refuses HTTPS→HTTP downgrades.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Maps a browser repository URL to its v5 API root.
     *
     * @throws IllegalArgumentException for hosts other than gitee.com or unparseable URLs
     */
    static String toApiBase(String giteeUrl) {
        Matcher m = HTTPS_URL.matcher(giteeUrl == null ? "" : giteeUrl.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Unsupported repository URL for Gitee update check: " + giteeUrl);
        }
        return "https://gitee.com/api/v5/repos/" + m.group(1) + "/" + m.group(2);
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
            throw new IllegalStateException("No Gitee API base derivable from app.gitee-url");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "jync-update-check")
                .GET()
                .build();
        var response = http.send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw ReleaseFeed.apiFailure(ReleaseFeed.Source.GITEE, response.statusCode());
        }
        return parse(response.body(), owner, repo);
    }

    /** Parses one Gitee release JSON document; package-private for tests (no sockets). */
    static Release parse(String body, String owner, String repo) throws Exception {
        JsonNode json = new ObjectMapper().readTree(body);
        String tag = json.path("tag_name").asText(null);
        if (tag == null) {
            throw new IllegalStateException("Gitee API response is missing tag_name");
        }
        String htmlUrl = "https://gitee.com/" + owner + "/" + repo + "/releases/tag/"
                + URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("+", "%20");

        List<Asset> assets = new ArrayList<>();
        for (JsonNode asset : json.path("assets")) {
            String name = asset.path("name").asText(null);
            String downloadUrl = asset.path("browser_download_url").asText(null);
            if (name != null && downloadUrl != null) {
                // Gitee v5 payloads carry no asset size: null stays null and verification
                // relies on zip integrity instead of a byte count.
                assets.add(new Asset(name, downloadUrl, null));
            }
        }

        Instant publishedAt = null;
        String created = json.path("created_at").asText(null);
        if (created != null && !created.isBlank()) {
            try {
                publishedAt = OffsetDateTime.parse(created).toInstant();
            } catch (java.time.format.DateTimeParseException e) {
                // created_at is display metadata only. Letting an unexpected format propagate
                // would discard the whole release and degrade the update check to UNKNOWN for
                // a full TTL; the version itself is still usable without a date.
                log.warn("Gitee release {} carries an unparseable created_at '{}';"
                        + " showing it without a date", tag, created);
            }
        }
        return new Release(tag, htmlUrl, publishedAt, json.path("body").asText(""),
                List.copyOf(assets), Source.GITEE);
    }
}
