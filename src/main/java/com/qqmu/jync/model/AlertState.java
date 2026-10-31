package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * What the evaluator last said about one (category, project, object) triple.
 *
 * <p>The row is the deduplication point across nodes: whichever node notices a fault first
 * writes {@code active = true} and sends, and the others find the row active and stay quiet
 * until the cooldown expires. A cleared condition keeps the row with {@code active = false},
 * preserving the last times for the next fault's cooldown arithmetic.
 */
@Entity
@Table(name = "alert_state", uniqueConstraints = @UniqueConstraint(
        name = "uk_alert_state_key",
        columnNames = {"category", "project_id", "object_key"}))
@Getter
@Setter
public class AlertState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AlertCategory category;

    /** 0 for alerts that belong to no project (a connection's health). */
    @Column(name = "project_id", nullable = false)
    private Long projectId = 0L;

    /** Empty for project-wide conditions; the table name for table-level ones. */
    @Column(name = "object_key", nullable = false, length = 256)
    private String objectKey = "";

    /** The project (or connection) name at the time the fault started, so a recovery can name it. */
    @Column(length = 256)
    private String subject;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "first_seen_at")
    private Instant firstSeenAt;

    @Column(name = "last_notified_at")
    private Instant lastNotifiedAt;

    /**
     * The newest event already notified for an event category, so the same change-log row is
     * never announced twice. Unused by condition categories.
     */
    @Column(name = "last_event_at")
    private Instant lastEventAt;
}
