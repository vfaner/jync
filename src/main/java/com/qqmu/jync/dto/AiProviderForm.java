package com.qqmu.jync.dto;

import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.AiReasoningEffort;

import lombok.Getter;
import lombok.Setter;

/**
 * What the AI provider form is allowed to submit.
 *
 * <p>{@code enabled}, {@code apiKey} and the last-probe columns are absent on purpose: enabling
 * has its own endpoint (and switches the others off inside a transaction), the key arrives as
 * {@code rawApiKey} and is encrypted by the service, and a crafted POST must not be able to fake
 * a successful probe.
 */
@Getter
@Setter
public class AiProviderForm {

    private Long id;
    private String name;
    private AiProtocol protocol = AiProtocol.OPENAI;
    private String baseUrl;
    private String model;
    private Integer maxTokens = 4096;
    private Integer timeoutSeconds = 60;
    private AiReasoningEffort reasoningEffort = AiReasoningEffort.DEFAULT;

    /** A fresh entity carrying only the submitted fields. */
    public AiProvider toEntity() {
        AiProvider provider = new AiProvider();
        provider.setId(id);
        provider.setName(name);
        provider.setProtocol(protocol);
        provider.setBaseUrl(baseUrl);
        provider.setModel(model);
        provider.setMaxTokens(maxTokens);
        provider.setTimeoutSeconds(timeoutSeconds);
        provider.setReasoningEffort(reasoningEffort);
        return provider;
    }
}
