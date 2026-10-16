package com.qqmu.jync.model;

import lombok.Getter;

/**
 * 让模型"深度思考"到什么程度。
 *
 * <p>为什么不是一个布尔开关:"关"在各家端点上的写法并不统一,所以这里存的是<b>意图</b>,
 * 翻译成请求体是 {@link com.qqmu.jync.service.ai.AiChatClient} 的事。
 * <ul>
 *   <li>OpenAI 形状的端点(含 Ollama、vLLM 等兼容服务)用 {@code reasoning_effort}。
 *       本机 Ollama 0.33.2 + qwen3:8b 实测认 {@code "none"}:带它时 4 个 token、零思考、
 *       正文正常;不带时思考直接吃掉整个输出预算、正文为空。</li>
 *   <li>Anthropic 用 {@code thinking.budget_tokens},而"关"就是省略该字段(它的默认就是不思考)。</li>
 *   <li>官方 OpenAI 的推理模型并非都认 {@code none}:o 系列的地板是 {@code low}(不是
 *       {@code minimal}),{@code none} 自 gpt-5.1 起才有效,gpt-5.0 是
 *       {@code minimal/low/medium/high}。不认的值端点以 {@code 400 unsupported_value}
 *       回绝 —— 所以 OFF 只对兼容端点承诺得死,这一条在帮助文案
 *       {@code ai.reasoningHelp} 里对用户写明了。</li>
 * </ul>
 *
 * <p>{@link #DEFAULT} 是唯一安全的默认值:它一个字段都不发,行为与"没有这个设置"完全一致。
 * 这一点是硬要求 —— 老数据(该列是 null)不能因为这次功能而改变任何一个请求的行为。
 */
@Getter
public enum AiReasoningEffort {

    /** 跟随端点/模型自己的默认 —— 一个字段都不发。 */
    DEFAULT(null, null),

    /** 明确不要思考。Ollama/vLLM 这类兼容端点认 {@code none}。 */
    OFF("none", null),

    /** 想一点,但把预算收住。 */
    LOW("low", 2048),

    /** 尽着预算想。 */
    HIGH("high", 8192);

    /** OpenAI 形状端点的 {@code reasoning_effort} 取值;null 表示不发该字段。 */
    private final String effortLevel;

    /** Anthropic 的 {@code thinking.budget_tokens};null 表示不发 thinking。 */
    private final Integer thinkingBudget;

    AiReasoningEffort(String effortLevel, Integer thinkingBudget) {
        this.effortLevel = effortLevel;
        this.thinkingBudget = thinkingBudget;
    }

    /**
     * 需要 Anthropic 思考预算的档位 —— 也就是存档前必须校验
     * {@code maxTokens > budget} 的那几档(否则 Anthropic 直接 400)。
     */
    public boolean needsThinkingBudget() {
        return thinkingBudget != null;
    }
}
