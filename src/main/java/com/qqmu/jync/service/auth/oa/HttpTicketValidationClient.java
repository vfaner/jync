package com.qqmu.jync.service.auth.oa;

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
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.service.auth.ExternalIdentity;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link TicketValidationClient} over {@code java.net.http}, with the same security constraints as
 * {@link HttpOaAuthClient}: http only after explicit consent, redirects never followed, a 64 KB
 * response cap, and neither the ticket nor the response body written to the log.
 */
@Slf4j
@Component
public class HttpTicketValidationClient implements TicketValidationClient {

    private static final String DEFAULT_TICKET_PARAM = "ticket";

    /**
     * One client for every validation, for the same reason as {@link HttpOaAuthClient}: the
     * instance owns a selector thread and a connection pool. The per-call deadline stays on
     * the request; the connect timeout only bounds the TCP handshake.
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public OaAuthResult validate(OaAuthConfig config, String ticket) {
        String base = OaText.trimToNull(config.getValidateUrl());
        if (base == null) {
            return OaAuthResult.failure("error.oa.ticket.urlRequired");
        }
        boolean post = config.getValidateMethod() == OaAuthConfig.ValidateMethod.POST;
        String param = config.getTicketParam() == null || config.getTicketParam().isBlank()
                ? DEFAULT_TICKET_PARAM : config.getTicketParam().trim();
        String encoded = URLEncoder.encode(ticket == null ? "" : ticket, StandardCharsets.UTF_8);
        URI uri;
        try {
            uri = post ? URI.create(base)
                    : URI.create(base + (base.contains("?") ? "&" : "?") + param + "=" + encoded);
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
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "application/json, text/plain");
            request = post
                    ? builder.header("Content-Type",
                                    "application/x-www-form-urlencoded; charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    param + "=" + encoded, StandardCharsets.UTF_8))
                            .build()
                    : builder.GET().build();
        } catch (IllegalArgumentException ex) {
            return OaAuthResult.failure("error.oa.url.invalid");
        }

        long started = System.nanoTime();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
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
            body = HttpBodies.readCapped(in);
        } catch (IOException ex) {
            return OaAuthResult.failure("error.oa.unreachable");
        }

        log.debug("Ticket validation host={} status={} elapsedMs={}", uri.getHost(),
                response.statusCode(), Duration.ofNanos(System.nanoTime() - started).toMillis());

        if (body == null) {
            return OaAuthResult.failure("error.oa.responseTooLarge");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return OaAuthResult.failure("error.oa.rejected");
        }

        JsonNode root;
        try {
            root = body.length == 0 ? null : objectMapper.readTree(body);
        } catch (IOException ex) {
            return OaAuthResult.failure("error.oa.badResponse");
        }

        String successField = OaText.trimToNull(config.getSuccessField());
        if (successField != null) {
            String actual = JsonFieldReader.textAt(root, successField);
            String expected = OaText.trimToNull(config.getSuccessValue());
            if (actual == null || expected == null || !actual.equals(expected)) {
                return OaAuthResult.failure("error.oa.rejected");
            }
        }

        // Same normalization as the password client and the header filter: remote text must not
        // carry a control character into the session principal.
        String username = ExternalIdentity.sanitize(
                JsonFieldReader.textAt(root, config.getUserField()));
        if (username == null) {
            return OaAuthResult.failure("error.oa.user.missing");
        }
        boolean admin = JsonFieldReader.matches(
                root, config.getAdminField(), config.getAdminValue());
        return OaAuthResult.success(username, admin);
    }
}
