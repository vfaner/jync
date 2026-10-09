package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * P1: the AI base URL is admin-typed input that drives an outbound request — scheme, host,
 * userinfo and metadata-address rules.
 */
class AiEndpointValidatorTest {

    @Test
    void acceptsOrdinaryHttpsEndpoints() {
        assertThatCode(() -> AiEndpointValidator.validateBaseUrl("https://api.example.com/v1"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsLoopbackLocalModelServers() {
        // Ollama and LM Studio serve on loopback; blocking that would kill a core use case.
        assertThat(AiEndpointValidator.isPermitted("http://127.0.0.1:11434/v1")).isTrue();
        assertThat(AiEndpointValidator.isPermitted("http://localhost:1234/v1")).isTrue();
    }

    @Test
    void acceptsPrivateIntranetEndpoints() {
        assertThat(AiEndpointValidator.isPermitted("http://10.20.30.40:8080/v1")).isTrue();
        assertThat(AiEndpointValidator.isPermitted("https://192.168.1.5/v1")).isTrue();
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
    void rejectsIpv6LinkLocalAndMetadataHostnames() {
        assertThatThrownBy(() ->
                AiEndpointValidator.validateBaseUrl("http://[fe80::1]/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ai.baseUrl.blocked");
        assertThatThrownBy(() ->
                AiEndpointValidator.validateBaseUrl("http://metadata.google.internal/compute"))
                .hasMessage("error.ai.baseUrl.blocked");
    }
}
