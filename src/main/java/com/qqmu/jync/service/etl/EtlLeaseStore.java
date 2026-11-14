package com.qqmu.jync.service.etl;

/**
 * The shared half of the ETL execution guard: a lease row per task or batch, so an instance
 * cannot start a run that another instance started.
 *
 * <p>Every operation acts on a row that may not exist; none of them is expected to block. The
 * guard treats any exception as "not acquired" — with the metadata store unreachable there is no
 * way to rule out a run elsewhere, and starting a second copy of an overwrite run is worse than
 * asking the operator to retry.
 */
public interface EtlLeaseStore {

    /**
     * Whether a lease row exists at all, expired or not. This is the acquire path's signal: an
     * expired row is taken over by {@link #claim}, not by a fresh {@link #insert}.
     */
    boolean exists(String key);

    /**
     * Whether an unexpired lease holds the key — what the destructive operations ask before
     * deleting a task or changing its extraction SQL. A row whose owner was killed mid-run stays
     * behind with an expiry in the past; it is not a live run and must not block them.
     */
    boolean isLive(String key);

    /**
     * Creates the row. Racing owners both see {@link #exists} false; the primary key decides,
     * and the loser gets an exception rather than a silent overwrite.
     */
    void insert(String key, String owner, long ttlMs);

    /**
     * Takes the row when its owner is this one or its lease has expired.
     *
     * @return true when this owner now holds it
     */
    boolean claim(String key, String owner, long ttlMs);

    /** Extends this owner's lease. False means the row is gone or belongs to someone else. */
    boolean renew(String key, String owner, long ttlMs);

    /**
     * Removes this owner's row; a non-owner's call does nothing. The next runner therefore
     * starts from {@link #insert}, which is also what its {@link #exists} check leads to.
     */
    void release(String key, String owner);

    /** Removes the row whoever owns it — only for a task or batch that was just deleted. */
    void discard(String key);
}
