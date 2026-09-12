package com.synctool.service.version;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads releases from the GitHub REST API using the JDK's built-in HTTP client, following
 * the same timeout/redirect discipline as {@code AiChatClient}.
 *
 * <p>The API base is derived from the configured repository URL ({@code app.github-url}),
 * so changing the repository does not require a second property. Hosts other than
 * github.com are rejected rather than guessing another vendor's API layout.
 */
@Component
@Slf4j
public class GithubReleaseFeed implements ReleaseFeed {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** https://github.com/owner/repo(.git)(/) or http variant. */
    private static final Pattern HTTPS_URL =
            Pattern.compile("^https?://github\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?/?$");

    /** git@github.com:owner/repo(.git) */
    private static final Pattern SSH_URL =
            Pattern.compile("^git@github\\.com:([^/]+)/([^/#?:]+?)(?:\\.git)?/?$");

    private final String apiBase;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GithubReleaseFeed(@Value("${app.github-url}") String githubUrl) {
        String parsed;
        try {
            parsed = toApiBase(githubUrl);
        } catch (IllegalArgumentException e) {
            // A misconfigured repository URL must not stop the application from booting;
            // every check then just reports UNKNOWN.
            log.warn("Update check disabled: {}", e.getMessage());
            parsed = null;
        }
        this.apiBase = parsed;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
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
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "synctool-update-check")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub API responded " + response.statusCode());
        }
        JsonNode json = mapper.readTree(response.body());
        String tag = json.path("tag_name").asText(null);
        String htmlUrl = json.path("html_url").asText(null);
        String published = json.path("published_at").asText(null);
        String body = json.path("body").asText("");
        if (tag == null || htmlUrl == null) {
            throw new IllegalStateException("GitHub API response is missing tag_name/html_url");
        }
        Instant publishedAt = null;
        if (published != null && !published.isBlank()) {
            publishedAt = Instant.parse(published);
        }
        return new Release(tag, htmlUrl, publishedAt, body);
    }
}
