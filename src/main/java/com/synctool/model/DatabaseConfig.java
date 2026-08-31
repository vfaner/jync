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

/** A JDBC connection definition for either a source or a target database. */
@Entity
@Table(name = "database_config")
@Getter
@Setter
public class DatabaseConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DatabaseType type = DatabaseType.MYSQL;

    @Column(length = 255)
    private String host;

    private Integer port;

    /** Database name, SID or service name depending on the product. */
    @Column(name = "database_name", length = 255)
    private String databaseName;

    /** Schema to read from / write to. Defaults to the connection user when blank. */
    @Column(name = "schema_name", length = 128)
    private String schemaName;

    @Column(length = 255)
    private String username;

    /** Stored encrypted; see {@link com.synctool.util.CryptoUtil}. */
    @Lob
    @Column(name = "password_enc")
    private String password;

    /** Full JDBC URL, used when {@link #type} is {@link DatabaseType#CUSTOM}. */
    @Lob
    @Column(name = "custom_url")
    private String customUrl;

    @Column(name = "custom_driver", length = 255)
    private String customDriver;

    /** Server-local path to a driver jar loaded at runtime. */
    @Column(name = "custom_jar_path", length = 1024)
    private String customJarPath;

    /** Extra JDBC URL parameters, appended as {@code k=v&k=v}. */
    @Lob
    @Column(name = "extra_params")
    private String extraParams;

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
