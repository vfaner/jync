package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

import com.qqmu.jync.util.DurationFormat;

/** An audit record of one change applied to the target database. */
@Entity
@Table(name = "change_log", indexes = {
        @Index(name = "idx_changelog_project", columnList = "project_id"),
        @Index(name = "idx_changelog_time", columnList = "occurred_at")
})
@Getter
@Setter
public class ChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 32)
    private ObjectType objectType;

    @Column(name = "object_name", length = 256)
    private String objectName;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 16)
    private ChangeType changeType;

    @Column(name = "affected_rows")
    private Integer affectedRows;

    @Lob
    private String details;

    /** True when the change was applied successfully. */
    @Column(nullable = false)
    private Boolean success = true;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /**
     * The duration as a human label for the change-log table ({@code 99550 ms} → {@code 1m 39s}).
     * Hibernate maps this entity by field, so this getter adds no column.
     */
    public String getDurationLabel() {
        return durationMs == null ? null : DurationFormat.format(durationMs);
    }

    public static ChangeLog of(Long projectId, ObjectType objectType, String objectName,
                              ChangeType changeType, String details) {
        ChangeLog log = new ChangeLog();
        log.projectId = projectId;
        log.objectType = objectType;
        log.objectName = objectName;
        log.changeType = changeType;
        log.details = details;
        log.occurredAt = Instant.now();
        return log;
    }
}
