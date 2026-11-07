package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * A search-engine endpoint owned by the dedicated-migration module.
 *
 * <p>Deliberately not a {@link DatabaseConfig}: that entity describes JDBC endpoints and their
 * source/target roles. These connections speak the Elasticsearch REST protocol and are only
 * used by migration handlers.
 */
@Entity
@Table(name = "migration_connection", uniqueConstraints = {
        @UniqueConstraint(name = "uk_migration_connection_name", columnNames = "name")
})
@Getter
@Setter
public class MigrationConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MigrationConnectionKind kind;

    /** The endpoint URI. HTTP(S) for search engines, {@code mongodb(+srv)://} for MongoDB, JDBC for KingbaseES. */
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    /** MongoDB database, or the database named in a KingbaseES JDBC URL. Null for search engines. */
    @Column(name = "database_name", length = 255)
    private String databaseName;

    /** KingbaseES target schema; null means the connection's current schema. */
    @Column(name = "schema_name", length = 128)
    private String schemaName;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 16)
    private MigrationConnectionAuthType authType = MigrationConnectionAuthType.NONE;

    @Column(length = 255)
    private String username;

    @Lob
    @Column(name = "password_enc")
    private String passwordEnc;

    /** Either the Base64 API key or an {@code id:key} pair; Jync normalises it when sending. */
    @Lob
    @Column(name = "api_key_enc")
    private String apiKeyEnc;

    /** Extra driver options (Mongo URI options or JDBC parameters). Never holds credentials. */
    @Column(name = "extra_params", length = 512)
    private String extraParams;

    @Column(name = "verify_tls", nullable = false)
    private boolean verifyTls = true;

    @Column(name = "connect_timeout_ms", nullable = false)
    private int connectTimeoutMs = 5000;

    @Column(name = "request_timeout_ms", nullable = false)
    private int requestTimeoutMs = 60000;

    @Column(length = 512)
    private String description;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "opt_version")
    private Long optVersion;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.authType == null) {
            this.authType = MigrationConnectionAuthType.NONE;
        }
        if (this.connectTimeoutMs <= 0) {
            this.connectTimeoutMs = 5000;
        }
        if (this.requestTimeoutMs <= 0) {
            this.requestTimeoutMs = 60000;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
