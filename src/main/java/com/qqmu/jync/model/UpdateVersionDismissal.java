package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The single row recording which release an admin chose to ignore ("ignore this version").
 *
 * <p>A fixed primary key rather than a row per dismissal: ignoring a newer release replaces
 * the previous entry, and only the newest acknowledgement matters. Kept in the metadata
 * database so every admin of the instance agrees and a restart does not bring the dialog back;
 * it also stays out of configuration packages because it describes this installation, not its
 * connections and tasks.
 */
@Entity
@Table(name = "update_version_dismissal")
@Getter
@Setter
@NoArgsConstructor
public class UpdateVersionDismissal {

    /** There is never more than one row; the key makes that a database fact. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** Version without the leading {@code v}, e.g. {@code 2.1.0}. */
    @Column(name = "ignored_version", nullable = false, length = 64)
    private String ignoredVersion;

    @Column(name = "dismissed_at", nullable = false)
    private Instant dismissedAt;
}
