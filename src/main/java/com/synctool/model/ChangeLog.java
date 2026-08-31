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

import lombok.Getter;
import lombok.Setter;

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
