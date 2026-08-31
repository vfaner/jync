package com.synctool.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * Last-known definition of a source object, serialized as JSON.
 *
 * <p>Structural change detection diffs the live source metadata against these rows.
 * Because the snapshot is durable, structural changes that happened while the tool was
 * down are discovered on the next run rather than lost.
 */
@Entity
@Table(name = "metadata_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_snapshot_object",
                columnNames = {"project_id", "object_type", "object_name"}),
        indexes = @Index(name = "idx_snapshot_project", columnList = "project_id"))
@Getter
@Setter
public class MetadataSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 32)
    private ObjectType objectType;

    @Column(name = "object_name", nullable = false, length = 256)
    private String objectName;

    @Lob
    @Column(name = "snapshot_json")
    private String snapshotJson;

    /** Hash of {@link #snapshotJson}, compared first to avoid parsing unchanged objects. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "captured_at")
    private Instant capturedAt;
}
