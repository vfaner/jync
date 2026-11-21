package com.qqmu.jync.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.AuditEvent;
import com.qqmu.jync.repository.AuditEventRepository;
import com.qqmu.jync.util.CurrentActor;

/**
 * Writes to — and reads from — the admin audit trail.
 *
 * <p>Append-only by default: there is no update, and the only delete is the administrator's
 * explicit date-based {@link #purgeBefore}, which keeps today's rows and records itself. See
 * {@link AuditEventRepository}. Mutation callers record inside their own transaction, so an
 * audit row stands or falls with the change it describes: a rolled-back delete leaves no
 * claim that it happened.
 *
 * <p>Actors and details are sanitized on the way in because at least one caller (failed
 * logins) supplies attacker-controlled text: control characters are stripped and both fields
 * are capped at their column lengths.
 */
@Service
public class AdminAuditService {

    /** Column caps mirrored from {@link AuditEvent}; overlong input is truncated, not rejected. */
    static final int MAX_ACTOR = 64;
    static final int MAX_DETAIL = 1024;

    private final AuditEventRepository repository;

    public AdminAuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /** Records an event attributed to the signed-in user (or "system" outside a request). */
    @Transactional
    public void record(AuditAction action, String detail) {
        record(action, detail, currentUsername());
    }

    /**
     * Records an event under an explicit actor. Used by the login handlers, where the security
     * context is not (yet) populated — and for failures the name is whatever was typed into
     * the form, which is why it gets sanitized here.
     */
    @Transactional
    public void record(AuditAction action, String detail, String actor) {
        repository.save(AuditEvent.of(clean(actor, MAX_ACTOR, "system"),
                action, clean(detail, MAX_DETAIL, null)));
    }

    public long count() {
        return repository.count();
    }

    /** One page of events, newest first; id order because occurredAt ties within a burst. */
    public Page<AuditEvent> page(int page, int size) {
        return repository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    /**
     * Removes audit events on or before {@code day} (the whole selected day included) and
     * records the purge itself — actor, cutoff and row count — so the trim is accountable.
     * Today's events always survive; a day of today or later is refused.
     *
     * @return how many events were removed
     * @throws IllegalArgumentException {@code log.purge.date.invalid} for a null/too-new day
     */
    @Transactional
    public int purgeBefore(java.time.LocalDate day) {
        java.time.Instant cutoff = com.qqmu.jync.util.LogPurgeDates.cutoffFor(day);
        int removed = repository.deleteByOccurredAtBefore(cutoff);
        record(AuditAction.AUDIT_PURGE, com.qqmu.jync.service.audit.AuditDetail.of(
                "audit.detail.auditPurge", String.valueOf(removed), day.toString()));
        return removed;
    }

    /** Strips CR/LF and control characters, trims, caps the length; falls back when blank. */
    private static String clean(String value, int max, String fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isEmpty()) {
            return fallback;
        }
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private static String currentUsername() {
        return CurrentActor.username();
    }
}
