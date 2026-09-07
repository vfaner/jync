package com.synctool.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * A user who may sign in to the tool.
 *
 * <p>The table is named {@code app_user} because {@code USER} is a reserved word in several of the
 * databases this project targets, and the metadata store is H2 today but need not stay that way.
 *
 * <p><strong>The password is hashed, not encrypted.</strong> That is the opposite of how
 * {@link DatabaseConfig} and {@link AiProvider} store their credentials, and the difference is the
 * point: those have to be handed to a JDBC driver or an HTTP client in the clear, so they use the
 * reversible {@link com.synctool.util.CryptoUtil}. A login password only ever needs to be
 * <em>compared</em>, so storing something reversible would be giving away recoverability the
 * feature never asked for.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt hash. Never the plaintext, never a reversible ciphertext. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role = UserRole.VIEWER;

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
