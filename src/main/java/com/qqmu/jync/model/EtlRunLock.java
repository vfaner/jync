package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One shared ETL execution lease: who is running the task or batch behind {@link #lockKey}, and
 * until when that claim is valid.
 *
 * <p>The key is the same string the in-process guard uses — {@code "task:<id>"} or
 * {@code "batch:<id>"} — so the two layers agree on what one execution slot is. The row lives in
 * the metadata database so a second instance sees a run the first one started; the expiry lets a
 * node that died mid-run be replaced without an administrator deleting anything.
 *
 * <p>Not a column on {@code etl_task}: the very point is to keep a deleted task's slot from
 * being reusable while its run is still writing, and a dropped row would take the lease with it.
 */
@Entity
@Table(name = "etl_run_lock")
@Getter
@Setter
@NoArgsConstructor
public class EtlRunLock {

    /** {@code "task:<id>"} or {@code "batch:<id>"}; the primary key is the mutual exclusion. */
    @Id
    @Column(name = "lock_key", nullable = false, length = 64)
    private String lockKey;

    /** Identifies the process holding the lease — see {@code EtlExecutionGuard}. */
    @Column(name = "owner_id", nullable = false, length = 160)
    private String ownerId;

    /** When the claim stops being valid without renewal. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Present so Spring Data treats a freshly built instance as new and issues a real INSERT.
     * Without a version attribute the non-null string id would route {@code save()} through
     * {@code merge()}, which turns a lost insert race into an update of the other node's row —
     * both nodes would then believe they hold the lease.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
