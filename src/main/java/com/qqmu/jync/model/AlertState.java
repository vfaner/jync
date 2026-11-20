package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.Setter;

/**
 * What the evaluator last said about one (category, project, object) triple.
 *
 * <p>The row is the deduplication point across nodes. Reading it is not enough — two nodes read
 * the same values before either writes — so every announcement first claims the row with a
 * conditional update on this table (see {@code AlertStateRepository}); the node whose update
 * matches zero rows stays quiet. The first announcement of a fault is claimed by the unique key:
 * the losing insert fails, and its caller does not send.
 *
 * <p>A cleared row is flipped to {@code active = false} by the node that sends the recovery, and
 * pruned at the end of that evaluation. A later fault of the same kind writes fresh timestamps,
 * so nothing worth keeping is lost.
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
    @JdbcTypeCode(SqlTypes.VARCHAR)
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
