package com.qqmu.jync.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;

/**
 * P1: request-time defense in depth — a bad base URL is refused before any socket work, on both
 * the blocking and streaming paths.
 */
class AiChatClientGuardTest {

    private final AiChatClient client = new AiChatClient();

    @Test
    void completeRefusesABlockedUrlWithoutTouchingTheNetwork() {
        AiChatClient.ChatResult result = client.complete(provider("http://169.254.169.254"),
                "key", null, "ping", 1);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("error.ai.baseUrl.blocked");
    }

    @Test
    void streamRefusesABlockedUrlWithoutTouchingTheNetwork() {
        AiChatClient.ChatResult result = client.stream(provider("http://169.254.169.254"),
                "key", null, "ping", 1, delta -> { });

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("error.ai.baseUrl.blocked");
    }

    private AiProvider provider(String baseUrl) {
        AiProvider provider = new AiProvider();
        provider.setName("p");
        provider.setProtocol(AiProtocol.OPENAI);
        provider.setBaseUrl(baseUrl);
        provider.setModel("model-a");
        provider.setTimeoutSeconds(5);
        return provider;
    }
}
