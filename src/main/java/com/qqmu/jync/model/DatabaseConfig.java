package com.qqmu.jync.model;

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

    /**
     * SQL family a CUSTOM connection speaks. The app cannot infer it from a custom JDBC URL,
     * and generic ANSI SQL is wrong for most real products, so the user picks one. Ignored —
     * and cleared on save — for every preset, which already declares its own family.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "dialect_family", length = 16)
    private DatabaseType.DialectFamily dialectFamily;

    /**
     * The product whose behavior this connection should get. A preset is its own; a CUSTOM
     * connection becomes the preset that speaks its declared family, because the type alone
     * carries GENERIC and every product-specific decision (type mapping, schema folding,
     * identity handling) would then silently take the generic path. A CUSTOM connection with
     * no declared family keeps {@link DatabaseType#CUSTOM}, whose dialect is the generic one.
     */
    public DatabaseType effectiveType() {
        if (type != DatabaseType.CUSTOM) {
            return type;
        }
        DatabaseType representative = DatabaseType.representativeOf(resolvedFamily());
        return representative == null ? DatabaseType.CUSTOM : representative;
    }

    /**
     * Whether a batched upsert against this connection reports affected rows precisely enough
     * to tell an inserted row from an updated one. Presets answer from their own URL template;
     * a CUSTOM connection answers from the URL the user actually supplied — the preset's flag
     * says nothing about a pasted URL that lacks it.
     */
    public boolean upsertCountsDistinguishInsertFromUpdate() {
        if (type == DatabaseType.CUSTOM) {
            return customUrl != null
                    && customUrl.toLowerCase(java.util.Locale.ROOT)
                            .contains("useaffectedrows=true");
        }
        return type != null && type.upsertCountsDistinguishInsertFromUpdate();
    }

    /** The SQL family this connection speaks: the preset's own, or CUSTOM's declared one. */
    public DatabaseType.DialectFamily resolvedFamily() {
        if (type == null) {
            return DatabaseType.DialectFamily.GENERIC;
        }
        if (type == DatabaseType.CUSTOM) {
            return dialectFamily == null
                    ? DatabaseType.DialectFamily.GENERIC : dialectFamily;
        }
        return type.getFamily();
    }

    /**
     * Source (read) or target (write); decides which section and project selector lists it in.
     * The column stays nullable at DDL level on purpose: ddl-auto=update adds it to existing
     * databases whose rows need the startup backfill, and H2 rejects adding a NOT NULL column
     * to a non-empty table. Application-level validation in DatabaseConfigService requires it.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ConnectionRole role = ConnectionRole.SOURCE;

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

    /** Stored encrypted; see {@link com.qqmu.jync.util.CryptoUtil}. */
    @Lob
    @Column(name = "password_enc")
    private String password;

    /**
     * P0-2: optional LogMiner mining account (Oracle CDB only). When set, streaming mines
     * through a second connection to CDB$ROOT as this common user (typically {@code C##…}),
     * while the main connection keeps reading the PDB for baseline/metadata. Blank →
     * single-account mode (non-CDB sources, the common case).
     */
    @Column(name = "miner_username", length = 255)
    private String minerUsername;

    /** Stored encrypted like {@link #password}; blank on update keeps the existing secret. */
    @Lob
    @Column(name = "miner_password_enc")
    private String minerPassword;

    /** Full JDBC URL; required for {@link DatabaseType#CUSTOM}, overrides the generated URL otherwise. */
    @Lob
    @Column(name = "custom_url")
    private String customUrl;

    /**
     * Driver class to load instead of the preset one. Required for {@link DatabaseType#CUSTOM};
     * for other types it overrides the preset class, which is how a vendor jar that declares a
     * different class name is connected (for example GaussDB's {@code gsjdbc200.jar}, whose
     * driver is {@code com.huawei.gauss200.jdbc.Driver}). Blank means "use the preset".
     */
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
