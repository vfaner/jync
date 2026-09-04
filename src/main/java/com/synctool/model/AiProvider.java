package com.synctool.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * An AI endpoint used to draft stored-procedure conversions.
 *
 * <p>Several may be configured but at most one is {@link #enabled}; the enabled one is what
 * {@link com.synctool.service.ai.AiProviderService#activeProvider()} hands out. Exclusivity is
 * enforced in the service inside a transaction, not in the browser, so two open tabs cannot
 * both switch one on.
 *
 * <p>The last probe result is stored rather than re-probed on render. Listing providers must
 * not reach out to the network: this tool's headline guarantee is that it works on an isolated
 * intranet and makes no outbound request unless asked to.
 */
@Entity
@Table(name = "ai_provider")
@Getter
@Setter
public class AiProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AiProtocol protocol = AiProtocol.OPENAI;

    /** Endpoint root, e.g. {@code https://api.deepseek.com/v1} or an intranet address. */
    @Column(name = "base_url", length = 512)
    private String baseUrl;

    @Column(length = 128)
    private String model;

    /** Stored encrypted; see {@link com.synctool.util.CryptoUtil}. */
    @Lob
    @Column(name = "api_key_enc")
    private String apiKey;

    /** At most one row is enabled at a time. */
    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "max_tokens")
    private Integer maxTokens = 4096;

    /**
     * Applied to both the connect and the request phase. A conversion is one large
     * completion, so the default is generous compared with a JDBC probe.
     */
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 60;

    @Column(name = "last_test_at")
    private Instant lastTestAt;

    /** Null means never probed, which the UI shows differently from a failure. */
    @Column(name = "last_test_ok")
    private Boolean lastTestOk;

    @Column(name = "last_test_message", length = 512)
    private String lastTestMessage;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
