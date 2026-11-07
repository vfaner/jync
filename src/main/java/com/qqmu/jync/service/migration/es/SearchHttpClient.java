package com.qqmu.jync.service.migration.es;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;

/**
 * Minimal Elasticsearch/OpenSearch REST client bound to one dedicated connection.
 *
 * <p>Redirects are never followed: a 3xx must not carry the Authorization header to another
 * host. Non-2xx responses become {@link MigrationHttpException} with status, method and path,
 * a 2 KiB body and every configured secret redacted.
 */
public class SearchHttpClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERROR_BODY = 2048;

    private final HttpClient client;
    private final SSLContext insecureTlsContext;
    private final int connectTimeoutMs;
    private final String baseUrl;
    private final String authorization;
    private final List<String> secrets;
    private final Duration requestTimeout;

    public SearchHttpClient(MigrationConnection connection, String plainPassword,
                            String plainApiKey) {
        this.baseUrl = normalizeBaseUrl(connection.getBaseUrl());
        this.requestTimeout = Duration.ofMillis(connection.getRequestTimeoutMs());
        this.connectTimeoutMs = connection.getConnectTimeoutMs();
        // java.net.http.HttpClient hard-codes endpoint identification back to HTTPS and the
        // only switch is a JVM-wide system property, so an opt-out has to use
        // HttpsURLConnection, which verifies the host name per connection.
        boolean insecure = !connection.isVerifyTls()
                && baseUrl.toLowerCase(Locale.ROOT).startsWith("https://");
        if (insecure) {
            try {
                this.insecureTlsContext = trustAllContext();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Could not disable TLS verification", e);
            }
            this.client = null;
        } else {
            this.insecureTlsContext = null;
            this.client = buildClient(connection);
        }
        this.authorization = buildAuthorization(connection, plainPassword, plainApiKey);
        this.secrets = new ArrayList<>();
        addSecret(plainPassword);
        addSecret(plainApiKey);
    }

    private void addSecret(String secret) {
        if (secret != null && !secret.isBlank()) {
            secrets.add(secret);
        }
    }

    /** Validates and canonicalises a base URL; the connection service stores the result. */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl is invalid", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("baseUrl must use http or https");
        }
        if (uri.getHost() == null || uri.getRawAuthority() == null) {
            throw new IllegalArgumentException("baseUrl must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must not contain credentials");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain a query or fragment");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return scheme + "://" + uri.getRawAuthority() + path;
    }

    private static HttpClient buildClient(MigrationConnection connection) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connection.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static SSLContext trustAllContext() throws GeneralSecurityException {
        TrustManager[] trustAll = new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());
        return context;
    }

    private static String buildAuthorization(MigrationConnection connection, String password,
                                             String apiKey) {
        MigrationConnectionAuthType authType = connection.getAuthType() == null
                ? MigrationConnectionAuthType.NONE : connection.getAuthType();
        switch (authType) {
            case BASIC:
                String username = connection.getUsername() == null ? "" : connection.getUsername();
                String secret = password == null ? "" : password;
                String token = Base64.getEncoder().encodeToString(
                        (username + ":" + secret).getBytes(StandardCharsets.UTF_8));
                return "Basic " + token;
            case API_KEY:
                String key = apiKey == null ? "" : apiKey.trim();
                // A pasted "id:key" pair is not valid Base64 yet; a Base64 key never contains ':'.
                if (key.contains(":")) {
                    key = Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8));
                }
                return "ApiKey " + key;
            case NONE:
            default:
                return null;
        }
    }

    public SearchResponse request(String method, String path, String body, String contentType) {
        SearchResponse response = send(method, path, body, contentType);
        if (!response.isSuccessful()) {
            throw failure(method, path, response);
        }
        return response;
    }

    public JsonNode get(String path) {
        return request("GET", path, null, null).json();
    }

    public JsonNode postJson(String path, Object body) {
        return request("POST", path, json(body), "application/json").json();
    }

    public JsonNode putJson(String path, Object body) {
        return request("PUT", path, json(body), "application/json").json();
    }

    public void delete(String path) {
        request("DELETE", path, null, null);
    }

    public JsonNode search(String path, JsonNode body) {
        return postJson(path, body);
    }

    /** HEAD existence check used by the write-mode rules. */
    public boolean exists(String path) {
        SearchResponse response = send("HEAD", path, null, null);
        if (response.status() == 404) {
            return false;
        }
        if (!response.isSuccessful()) {
            throw failure("HEAD", path, response);
        }
        return true;
    }

    public BulkResult bulk(List<String> ndjsonLines) {
        String body = String.join("\n", ndjsonLines) + "\n";
        SearchResponse response = request("POST", "/_bulk", body, "application/x-ndjson");
        JsonNode root = response.json();
        if (root == null || !root.path("errors").asBoolean(false)) {
            return BulkResult.success();
        }
        List<BulkResult.FailedItem> failures = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            for (JsonNode action : item) {
                JsonNode error = action.get("error");
                int status = action.path("status").asInt(200);
                if (error != null || status >= 300) {
                    String id = action.path("_id").asString("");
                    String reason = error == null ? ("status " + status)
                            : error.path("type").asString("error") + ": "
                                    + error.path("reason").asString("");
                    failures.add(new BulkResult.FailedItem(id, sanitize(reason)));
                }
            }
        }
        return new BulkResult(true, failures);
    }

    public void clearScroll(String scrollId) {
        request("DELETE", "/_search/scroll",
                "{\"scroll_id\":[\"" + escape(scrollId) + "\"]}", "application/json");
    }

    private SearchResponse send(String method, String path, String body, String contentType) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        if (client == null) {
            return sendWithoutHostnameVerification(method, path, body, contentType);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", contentType == null ? "application/json" : contentType);
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return toResponse(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new MigrationHttpException(method, path, 0, "I/O error",
                    sanitize(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MigrationHttpException(method, path, 0, "interrupted", null);
        }
    }

    /**
     * The {@code verifyTls=false} path. HttpsURLConnection applies the host name verifier and
     * socket factory per connection, which HttpClient cannot do without a JVM-wide property.
     */
    private SearchResponse sendWithoutHostnameVerification(String method, String path,
                                                           String body, String contentType) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(baseUrl + path).openConnection();
            connection.setSSLSocketFactory(insecureTlsContext.getSocketFactory());
            connection.setHostnameVerifier((hostname, session) -> true);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout((int) requestTimeout.toMillis());
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (authorization != null) {
                connection.setRequestProperty("Authorization", authorization);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type",
                        contentType == null ? "application/json" : contentType);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            String responseBody = "";
            InputStream stream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                try (InputStream in = stream) {
                    responseBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return toResponse(status, responseBody);
        } catch (IOException e) {
            throw new MigrationHttpException(method, path, 0, "I/O error",
                    sanitize(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static SearchResponse toResponse(int status, String responseBody) {
        String body = responseBody == null ? "" : responseBody;
        JsonNode json = null;
        if (!body.isBlank()) {
            try {
                json = JSON.readTree(body);
            } catch (tools.jackson.core.JacksonException ignored) {
                // A non-JSON error body is still useful as text.
            }
        }
        return new SearchResponse(status, status + " " + httpReason(status), body, json);
    }

    private MigrationHttpException failure(String method, String path, SearchResponse response) {
        return new MigrationHttpException(method, path, response.status(),
                response.reason(), sanitize(response.body()));
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String redacted = value;
        for (String secret : secrets) {
            redacted = redacted.replace(secret, "***");
        }
        return redacted.length() <= MAX_ERROR_BODY ? redacted
                : redacted.substring(0, MAX_ERROR_BODY) + "…";
    }

    private static String json(Object body) {
        if (body == null) {
            return "{}";
        }
        if (body instanceof String) {
            return (String) body;
        }
        try {
            return JSON.writeValueAsString(body);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("Could not serialize request body", e);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String httpReason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 201:
                return "Created";
            case 302:
                return "Found";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 409:
                return "Conflict";
            case 429:
                return "Too Many Requests";
            case 500:
                return "Internal Server Error";
            case 503:
                return "Service Unavailable";
            default:
                return "";
        }
    }

    @Override
    public void close() {
        // java.net.http.HttpClient has no close() on Java 17.
    }
}
