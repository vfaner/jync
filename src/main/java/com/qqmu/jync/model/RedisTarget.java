package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * A Redis endpoint used only as a {@link SyncTargetKind#REDIS} target.
 *
 * <p>Deliberately not a {@link DatabaseConfig}: that entity describes JDBC endpoints with a
 * source/target role and feeds every dialect, metadata and ETL path. Redis is a cache the CDC
 * consumers project rows into, never a source, so it gets its own connection entity and never
 * appears in a source dropdown.
 *
 * <p>The credential lives only in {@link #passwordEnc}; the edit form never receives it.
 */
@Entity
@Table(name = "redis_target", uniqueConstraints = {
        @UniqueConstraint(name = "uk_redis_target_name", columnNames = "name")
})
@Getter
@Setter
public class RedisTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private Integer port = 6379;

    /** Single-node database index. A cluster has only database 0. */
    @Column(name = "database_index", nullable = false)
    private Integer databaseIndex = 0;

    /** Redis 6 ACL user; null for the default user or password-only auth. */
    @Column(length = 255)
    private String username;

    @Lob
    @Column(name = "password_enc")
    private String passwordEnc;

    @Column(name = "tls_enabled", nullable = false)
    private boolean tlsEnabled;

    @Column(name = "verify_tls", nullable = false)
    private boolean verifyTls = true;

    @Column(name = "cluster_mode", nullable = false)
    private boolean clusterMode;

    @Column(name = "connect_timeout_ms", nullable = false)
    private int connectTimeoutMs = 5000;

    @Column(name = "command_timeout_ms", nullable = false)
    private int commandTimeoutMs = 10000;

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
        if (this.port == null || this.port <= 0) {
            this.port = 6379;
        }
        if (this.databaseIndex == null) {
            this.databaseIndex = 0;
        }
        if (this.connectTimeoutMs <= 0) {
            this.connectTimeoutMs = 5000;
        }
        if (this.commandTimeoutMs <= 0) {
            this.commandTimeoutMs = 10000;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
