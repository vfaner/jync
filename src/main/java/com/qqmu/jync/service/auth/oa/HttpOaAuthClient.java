package com.qqmu.jync.service.auth.oa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.OaAuthConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link OaAuthClient}: a plain POST built by {@code java.net.http}, with the request
 * shape and the response mapping taken from the administrator's configuration.
 *
 * <p>Security properties that must survive refactoring: plain http only after explicit consent,
 * redirects never followed (an internal endpoint must not bounce the password elsewhere), the
 * response capped at 64 KB, and neither the password nor the body ever logged.
 */
@Slf4j
@Component
public class HttpOaAuthClient implements OaAuthClient {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_USERNAME_LENGTH = 64;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public OaAuthResult authenticate(OaAuthConfig config, String username, String password) {
        URI uri;
        try {
            uri = URI.create(config.getLoginUrl() == null ? "" : config.getLoginUrl().trim());
        } catch (IllegalArgumentException ex) {
            return OaAuthResult.failure("error.oa.url.invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return OaAuthResult.failure("error.oa.url.invalid");
        }
        if (scheme.equals("http") && !config.isAllowInsecureHttp()) {
            return OaAuthResult.failure("error.oa.url.notHttps");
        }

        int timeoutMs = config.getTimeoutMs() == null ? 5000 : config.getTimeoutMs();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", contentType(config))
                    .header("Accept", "application/json, text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(body(config, username, password),
                            StandardCharsets.UTF_8))
                    .build();
        } catch (IOException ex) {
            return OaAuthResult.failure("error.oa.badResponse");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        long startedNanos = System.nanoTime();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException ex) {
            return OaAuthResult.failure("error.oa.unreachable");
        } catch (IOException ex) {
            return OaAuthResult.failure("error.oa.unreachable");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return OaAuthResult.failure("error.oa.unreachable");
        }

        byte[] body;
        try (InputStream in = response.body()) {
            body = readCapped(in);
        } catch (IOException ex) {
            return OaAuthResult.failure("error.oa.unreachable");
        }

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        log.debug("OA sign-in call host={} status={} elapsedMs={}",
                uri.getHost(), response.statusCode(), elapsedMs);

        if (body == null) {
            return OaAuthResult.failure("error.oa.responseTooLarge");
        }

        String successField = trimToNull(config.getSuccessField());
        if (successField == null) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return OaAuthResult.failure("error.oa.rejected");
            }
        }

        JsonNode root;
        try {
            root = body.length == 0 ? null : objectMapper.readTree(body);
        } catch (IOException ex) {
            return OaAuthResult.failure("error.oa.badResponse");
        }

        if (successField != null) {
            String actual = textAt(root, successField);
            String expected = trimToNull(config.getSuccessValue());
            if (actual == null || expected == null || !actual.equals(expected)) {
                return OaAuthResult.failure("error.oa.rejected");
            }
        }

        String resolvedUsername = textAt(root, config.getUserField());
        if (resolvedUsername == null) {
            return OaAuthResult.failure("error.oa.user.missing");
        }
        if (resolvedUsername.length() > MAX_USERNAME_LENGTH) {
            resolvedUsername = resolvedUsername.substring(0, MAX_USERNAME_LENGTH);
        }

        return OaAuthResult.success(resolvedUsername, isAdmin(config, root));
    }

    private static String contentType(OaAuthConfig config) {
        return config.getRequestFormat() == OaAuthConfig.RequestFormat.JSON
                ? "application/json; charset=UTF-8"
                : "application/x-www-form-urlencoded; charset=UTF-8";
    }

    /** The submitted credentials, in the shape the configured format expects. */
    private String body(OaAuthConfig config, String username, String password) throws IOException {
        String usernameField = orDefault(config.getUsernameField(), "username");
        String passwordField = orDefault(config.getPasswordField(), "password");
        if (config.getRequestFormat() == OaAuthConfig.RequestFormat.JSON) {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put(usernameField, username);
            payload.put(passwordField, password);
            return objectMapper.writeValueAsString(payload);
        }
        return URLEncoder.encode(usernameField, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(username == null ? "" : username, StandardCharsets.UTF_8) + "&"
                + URLEncoder.encode(passwordField, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(password == null ? "" : password, StandardCharsets.UTF_8);
    }

    /**
     * Reads at most {@link #MAX_RESPONSE_BYTES}; returns {@code null} as soon as the body proves
     * larger, so an endpoint cannot make the login thread buffer an arbitrary amount.
     */
    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_RESPONSE_BYTES) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Dot path lookup ({@code data.user}). Missing nodes, nulls and containers all count as
     * absent — only a scalar can name a user or carry a success marker.
     */
    private static String textAt(JsonNode root, String path) {
        if (root == null || path == null) {
            return null;
        }
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(part);
        }
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static boolean isAdmin(OaAuthConfig config, JsonNode root) {
        String adminField = trimToNull(config.getAdminField());
        String adminValue = trimToNull(config.getAdminValue());
        if (adminField == null || adminValue == null) {
            return false;
        }
        String actual = textAt(root, adminField);
        if (actual == null) {
            return false;
        }
        for (String accepted : adminValue.split(",")) {
            if (accepted.trim().equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static String orDefault(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
