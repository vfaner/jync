package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * One row of the append-only admin audit trail.
 *
 * <p>Unlike {@link ChangeLog} (what the sync engine did to the target database), this records
 * what <em>people</em> did to the application: who signed in, who changed or destroyed
 * configuration, who cleared history. There is deliberately no code path that updates or
 * deletes a row — {@link com.qqmu.jync.repository.AuditEventRepository} does not even expose
 * such a method — so an admin cannot erase their own footprints.
 */
@Entity
@Table(name = "audit_event", indexes = {
        @Index(name = "idx_audit_time", columnList = "occurred_at")
})
@Getter
@Setter
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Username the event is attributed to; sanitized by the service before it lands here. */
    @Column(nullable = false, length = 64)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuditAction action;

    /** Human-readable context (object names, counts, client IP). Never secrets. */
    @Column(length = 1024)
    private String detail;

    public static AuditEvent of(String actor, AuditAction action, String detail) {
        AuditEvent event = new AuditEvent();
        event.actor = actor;
        event.action = action;
        event.detail = detail;
        event.occurredAt = Instant.now();
        return event;
    }
}
