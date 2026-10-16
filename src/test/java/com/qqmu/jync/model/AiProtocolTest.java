package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Ollama 是"本地预设"而不是第三种协议:它说的是 OpenAI 那套话,只是预填地址和显示名不同。
 * 这些断言把预填值和回退行为钉住 —— 用户选了它却连不上,通常是预填地址错了。
 */
class AiProtocolTest {

    @Test
    void ollamaPrefillsTheLocalOpenAiCompatibleEndpoint() {
        assertThat(AiProtocol.OLLAMA.getDefaultBaseUrl())
                .as("Ollama 的 OpenAI 兼容端点在 /v1 上")
                .isEqualTo("http://localhost:11434/v1");
        assertThat(AiProtocol.OLLAMA.resolveEndpoint(AiProtocol.OLLAMA.getDefaultBaseUrl()))
                .as("预填值拼出来必须正好是兼容端点")
                .isEqualTo("http://localhost:11434/v1/chat/completions");
    }

    @Test
    void aStoredOllamaNameRoundTrips() {
        assertThat(AiProtocol.fromName("ollama")).isEqualTo(AiProtocol.OLLAMA);
        assertThat(AiProtocol.fromName(" OLLAMA ")).isEqualTo(AiProtocol.OLLAMA);
    }

    @Test
    void anUnknownStoredNameStillFallsBackToTheOpenAiShape() {
        // 新库(含 OLLAMA)落到老版本时走的就是这条路:回退到 OPENAI。
        // 因为 Ollama 说的正是同一套话,回退之后依然连得上 —— 降级安全。
        assertThat(AiProtocol.fromName("some-future-protocol")).isEqualTo(AiProtocol.OPENAI);
    }
}
