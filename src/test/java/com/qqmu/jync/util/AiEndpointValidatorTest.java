package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * P1: the AI base URL is admin-typed input that drives an outbound request — scheme, host,
 * userinfo and metadata-address rules.
 */
class AiEndpointValidatorTest {

    @Test
    void acceptsOrdinaryHttpsEndpoints() {
        // A literal host keeps this independent of the test machine's DNS.
        assertThatCode(() -> AiEndpointValidator.validateBaseUrl("https://10.1.2.3/v1"))
                .doesNotThrowAnyException();
    }

    /**
     * A name that does not resolve is refused, not passed through: the HTTP client re-resolves
     * at connect time, so "NXDOMAIN for the validator, an internal IP for the client" is the
     * DNS-rebinding shape. {@code .invalid} is reserved to never resolve.
     */
    @Test
    void rejectsAHostThatDoesNotResolve() {
        assertThatThrownBy(() -> AiEndpointValidator.validateBaseUrl(
                "https://no-such-host.invalid/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.unresolved");
    }

    @Test
    void acceptsLoopbackLocalModelServers() {
        // Ollama and LM Studio serve on loopback; blocking that would kill a core use case.
        assertThatCode(() -> AiEndpointValidator.validateBaseUrl("http://127.0.0.1:11434/v1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> AiEndpointValidator.validateBaseUrl("http://localhost:1234/v1"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsPrivateIntranetEndpoints() {
        assertThatCode(() -> AiEndpointValidator.validateBaseUrl("http://10.20.30.40:8080/v1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> AiEndpointValidator.validateBaseUrl("https://192.168.1.5/v1"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThatThrownBy(() -> AiEndpointValidator.validateBaseUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.scheme");
        assertThatThrownBy(() -> AiEndpointValidator.validateBaseUrl("gopher://host/x"))
                .hasMessage("error.ai.baseUrl.scheme");
    }

    @Test
    void rejectsEmbeddedUserinfo() {
        assertThatThrownBy(() -> AiEndpointValidator.validateBaseUrl("https://sk-abc@api.x.com/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.userinfo");
    }

    @Test
    void rejectsUrlsWithoutAHost() {
        assertThatThrownBy(() -> AiEndpointValidator.validateBaseUrl("http:///v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.invalid");
    }

    @Test
    void rejectsAMissingBaseUrlInsteadOfThrowingAnNpe() {
        // A partial POST (/api/ai/test without a baseUrl field) hands null straight here.
        // An NPE would escape the IllegalArgumentException channel and surface as a 500
        // instead of the dialog's own "invalid URL" message.
        assertThatThrownBy(() -> AiEndpointValidator.validateBaseUrl(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.invalid");
        assertThatThrownBy(() -> AiEndpointValidator.validateBaseUrl("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.invalid");
    }

    @Test
    void rejectsMalformedUrls() {
        assertThatThrownBy(() -> AiEndpointValidator.validateBaseUrl("https://api.exa mple.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.invalid");
    }

    @Test
    void rejectsCloudMetadataLiteralAddresses() {
        assertThatThrownBy(() ->
                AiEndpointValidator.validateBaseUrl("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.blocked");
        assertThatThrownBy(() ->
                AiEndpointValidator.validateBaseUrl("http://100.100.100.200/latest/meta-data"))
                .hasMessage("error.ai.baseUrl.blocked");
    }

    @Test
    void rejectsTheAwsIpv6MetadataAddress() {
        // fd00:ec2::254 is unique-local (fc00::/7), not link-local — the JDK's
        // isLinkLocalAddress misses it, so it needs an explicit block like the IPv4 endpoint.
        assertThatThrownBy(() -> AiEndpointValidator.validateBaseUrl("http://[fd00:ec2::254]/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.blocked");
    }

    @Test
    void rejectsIpv6LinkLocalAndMetadataHostnames() {
        assertThatThrownBy(() ->
                AiEndpointValidator.validateBaseUrl("http://[fe80::1]/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.blocked");
        assertThatThrownBy(() ->
                AiEndpointValidator.validateBaseUrl("http://metadata.google.internal/compute"))
                .hasMessage("error.ai.baseUrl.blocked");
    }

    /**
     * In tr-TR Java's locale-sensitive uppercase maps 'i' to 'İ' (U+0130), so a hostname
     * check written with {@code toUpperCase()} stops matching the blocklist. This name
     * resolves to a PUBLIC address, so only the list can refuse it — a link-local check
     * cannot step in.
     */
    @Test
    void rejectsMetadataHostnamesEvenUnderATurkishDefaultLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThatThrownBy(() ->
                    AiEndpointValidator.validateBaseUrl("https://metadata.googleapis.com/v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("error.ai.baseUrl.blocked");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
