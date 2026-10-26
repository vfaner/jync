package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The single row recording which published package an admin dismissed ("ignore this notice").
 *
 * <p>A fixed primary key rather than a row per dismissal: acknowledging a newer upload replaces
 * the previous acknowledgement, and nothing else ever reads the history. Kept in the metadata
 * database so every admin of the instance sees the same answer, and so a restart does not bring
 * the notice back.
 */
@Entity
@Table(name = "update_artifact_dismissal")
@Getter
@Setter
@NoArgsConstructor
public class UpdateArtifactDismissal {

    /** There is never more than one row; the key makes that a database fact. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** {@code "sha256:<hex>"} or {@code "size:<bytes>"} — see Asset#identity(). */
    @Column(name = "artifact_key", nullable = false, length = 160)
    private String artifactKey;

    @Column(name = "dismissed_at", nullable = false)
    private Instant dismissedAt;
}
