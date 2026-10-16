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

    /**
     * 原生端点的预填值。用户在表单里选它,得到的地址就是这里拼出来的 —— 拼错一个段,
     * 端点回的就是它自己的 404,而报错里只有那个 URL 能说明问题。
     */
    @Test
    void nativeOllamaPrefillsTheNativePathWithoutV1() {
        assertThat(AiProtocol.OLLAMA_NATIVE.getDefaultBaseUrl())
                .as("原生端点在 11434 根上,没有 /v1 那一段")
                .isEqualTo("http://localhost:11434");
        assertThat(AiProtocol.OLLAMA_NATIVE.resolveEndpoint(
                AiProtocol.OLLAMA_NATIVE.getDefaultBaseUrl()))
                .as("预填值拼出来必须正好是原生端点")
                .isEqualTo("http://localhost:11434/api/chat");
        assertThat(AiProtocol.OLLAMA_NATIVE.resolveEndpoint("http://localhost:11434/api/chat"))
                .as("用户把完整地址粘进来不该拼成 /api/chat/api/chat")
                .isEqualTo("http://localhost:11434/api/chat");
    }

    @Test
    void aStoredNativeNameRoundTrips() {
        // 表单提交的就是 name();存进去再读出来必须还是同一个成员。
        assertThat(AiProtocol.fromName("OLLAMA_NATIVE")).isEqualTo(AiProtocol.OLLAMA_NATIVE);
    }

    @Test
    void anUnknownStoredNameStillFallsBackToTheOpenAiShape() {
        // 新库(含 OLLAMA)落到老版本时走的就是这条路:回退到 OPENAI。
        // 因为 Ollama 说的正是同一套话,回退之后依然连得上 —— 降级安全。
        assertThat(AiProtocol.fromName("some-future-protocol")).isEqualTo(AiProtocol.OPENAI);
    }
}
