package com.qqmu.jync.service.etl;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Prevents the same ETL task from running twice at once — whether the second trigger is the
 * schedule firing again, an operator pressing "run now", or a second instance on another node.
 *
 * <p>Two layers, each covering what the other cannot:
 *
 * <ol>
 *   <li><b>An in-process set</b> answers "is this running here?" without touching the database,
 *       which is what the list pages and the destructive-operation checks need on every request.
 *       It also keeps two clicks in the same JVM from both reaching the database.
 *   <li><b>A lease row in the metadata database</b> ({@link EtlLeaseStore}) stops another node
 *       from starting the same run. Without it, two instances on one schedule would delete the
 *       same range, or insert the same rows, and each would report success. The lease expires
 *       and is renewed by a watchdog, so a node killed mid-run does not block its task forever.
 * </ol>
 *
 * <p>The shared lease is deliberately fail-closed: if the metadata store cannot be reached, the
 * run is refused. Starting an unleased overwrite run next to a possible live one is worse than
 * asking the operator to retry.
 */
@Component
@Slf4j
public class EtlExecutionGuard {

    /**
     * How long a lease survives without renewal. Short enough that a killed node's task is
     * runnable again in about a minute, long enough that a slow metadata write is not mistaken
     * for a dead owner.
     */
    static final long LEASE_TTL_MS = 60_000L;

    /** Renewal cadence: TTL/3, so two renewals may be lost before the lease expires. */
    private static final long RENEW_INTERVAL_MS = Math.max(1_000L, LEASE_TTL_MS / 3);

    /** Daemon: a hung renewal must never keep the JVM alive. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jync-etl-lease-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** Keys whose lease another node has taken; warned about once, never renewed again. */
    private final Set<String> lostLeases = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean watchdogStarted = new AtomicBoolean();

    /** Null for a guard that only needs to keep one JVM from double-running (tests). */
    private final EtlLeaseStore leaseStore;
    private final String ownerId;

    public EtlExecutionGuard() {
        this(null, "local");
    }

    @Autowired
    public EtlExecutionGuard(EtlLeaseStore leaseStore) {
        this(leaseStore, buildOwnerId());
    }

    EtlExecutionGuard(EtlLeaseStore leaseStore, String ownerId) {
        this.leaseStore = leaseStore;
        this.ownerId = ownerId;
        log.info("ETL execution guard owner id: {}", ownerId);
    }

    /** A task's slot. Batches use their own {@code "batch:<id>"} key through the string API. */
    public boolean tryAcquire(Long taskId) {
        return tryAcquire(taskKey(taskId));
    }

    public boolean tryAcquire(String key) {
        if (!running.add(key)) {
            return false;
        }
        if (leaseStore == null) {
            return true;
        }
        try {
            if (!leaseStore.exists(key)) {
                leaseStore.insert(key, ownerId, LEASE_TTL_MS);
                startWatchdog();
                return true;
            }
            if (leaseStore.claim(key, ownerId, LEASE_TTL_MS)) {
                lostLeases.remove(key);
                startWatchdog();
                return true;
            }
            running.remove(key);
            log.info("ETL key {} is held by another node; refusing to start a second run", key);
            return false;
        } catch (RuntimeException e) {
            // Includes losing the insert race: the other owner's row is the truth.
            running.remove(key);
            log.warn("Could not take the ETL lease for {} (another node may be running it); "
                    + "refusing to start: {}", key, e.getMessage());
            return false;
        }
    }

    public void release(Long taskId) {
        release(taskKey(taskId));
    }

    public void release(String key) {
        running.remove(key);
        lostLeases.remove(key);
        if (leaseStore == null) {
            return;
        }
        try {
            leaseStore.release(key, ownerId);
        } catch (RuntimeException e) {
            // The lease expires by itself; a failed release must not mask the run's outcome.
            log.warn("Could not release the ETL lease for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Takes the key for the duration of a definition mutation (delete, extraction edit,
     * batch membership change).
     *
     * <p>A plain {@link #isLeased} check followed by the mutation leaves a window: a run can
     * start after the check and before the commit, and the mutation then either clears the
     * checkpoint under the running query or drops a lease that is now live. Holding the key
     * across the whole mutation turns that run start into a refusal instead.
     *
     * <p>Inside a transaction the lease is released only after the transaction completes
     * (commit or rollback) — that is where the mutation becomes visible to other nodes.
     * Outside one it is released by {@link MutationLease#close()}.
     */
    public MutationLease lockForMutation(Long taskId) {
        return lockForMutation(taskKey(taskId));
    }

    public MutationLease lockForMutation(String key) {
        if (!tryAcquire(key)) {
            return new MutationLease(this, key, false, false);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            release(key);
                        }
                    });
            return new MutationLease(this, key, true, true);
        }
        return new MutationLease(this, key, true, false);
    }

    /**
     * The handle of a {@link #lockForMutation} acquisition. {@link #close()} releases an
     * immediate lease; a lease registered with the transaction releases in
     * {@code afterCompletion} instead, so closing early cannot reopen the window.
     */
    public static final class MutationLease implements AutoCloseable {
        private final EtlExecutionGuard guard;
        private final String key;
        private final boolean acquired;
        private final boolean deferred;

        private MutationLease(EtlExecutionGuard guard, String key, boolean acquired,
                              boolean deferred) {
            this.guard = guard;
            this.key = key;
            this.acquired = acquired;
            this.deferred = deferred;
        }

        /** False when the key is running here or leased by a live owner elsewhere. */
        public boolean acquired() {
            return acquired;
        }

        @Override
        public void close() {
            if (acquired && !deferred) {
                guard.release(key);
            }
        }
    }

    /**
     * Drops the key's lease row whoever owns it.
     *
     * <p>Called when the task or batch behind the key is deleted. Ordinary runs release on the
     * way out; the row only outlives them when a node died holding it, and that row would
     * otherwise linger until it expired.
     */
    public void discard(Long taskId) {
        discard(taskKey(taskId));
    }

    public void discard(String key) {
        running.remove(key);
        lostLeases.remove(key);
        if (leaseStore == null) {
            return;
        }
        try {
            leaseStore.discard(key);
        } catch (RuntimeException e) {
            log.warn("Could not discard the ETL lease for {}: {}", key, e.getMessage());
        }
    }

    /** Whether this node is running the key. Cheap; the UI calls it once per row. */
    public boolean isRunning(Long taskId) {
        return isRunning(taskKey(taskId));
    }

    public boolean isRunning(String key) {
        return running.contains(key);
    }

    /**
     * Whether the key is running here <em>or</em> leased by a live owner elsewhere.
     *
     * <p>The destructive operations use this — deleting a task, removing it from a batch,
     * changing its extraction SQL — so a definition cannot be pulled out from under a run on
     * another node. A read failure reports "free", because the store being down also makes every
     * start fail closed; there is nothing to protect against in that state.
     */
    public boolean isLeased(Long taskId) {
        return isLeased(taskKey(taskId));
    }

    public boolean isLeased(String key) {
        if (running.contains(key)) {
            return true;
        }
        if (leaseStore == null) {
            return false;
        }
        try {
            // isLive, not exists: a row left by a killed node is expired and must not keep its
            // task undeletable (nothing else ever removes such a row).
            return leaseStore.isLive(key);
        } catch (RuntimeException e) {
            log.warn("Could not read the ETL lease for {}; treating it as free: {}",
                    key, e.getMessage());
            return false;
        }
    }

    /**
     * Extends every lease this node holds. Package-private for tests; the watchdog calls it.
     *
     * <p>Renewal is unbounded on purpose: an ETL copy can legitimately run for hours, and a cap
     * would let a second run start beside a first that is still writing. A lost lease is
     * reported once and stops being renewed; the local slot stays held until the run finishes,
     * so this node cannot stack a third run on top of its own.
     */
    void renewLeases() {
        if (leaseStore == null) {
            return;
        }
        for (String key : running) {
            if (lostLeases.contains(key)) {
                continue;
            }
            boolean renewed;
            try {
                renewed = leaseStore.renew(key, ownerId, LEASE_TTL_MS);
            } catch (RuntimeException e) {
                // A briefly unreachable store must not surrender a lease we still hold.
                log.warn("Could not renew the ETL lease for {}: {}", key, e.getMessage());
                continue;
            }
            // release() may have removed the key while the renewal was in flight; that is a
            // normal finish, not a lost lease.
            if (!renewed && running.contains(key) && lostLeases.add(key)) {
                log.warn("The ETL lease for {} was taken over by another node; the run here "
                        + "continues but is no longer exclusive", key);
            }
        }
    }

    /** Starts the renewal timer on the first successful acquisition of this process. */
    private void startWatchdog() {
        if (watchdogStarted.compareAndSet(false, true)) {
            WATCHDOG.scheduleAtFixedRate(this::renewLeases,
                    RENEW_INTERVAL_MS, RENEW_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static String taskKey(Long taskId) {
        return "task:" + taskId;
    }

    /**
     * host:12-hex, with the random part as the collision guard: cloned VMs and container images
     * answer {@code getLocalHost()} with the same name, and two genuine instances sharing an
     * owner id would each read the other's lease as their own.
     */
    private static String buildOwnerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        if (host.length() > 120) {
            host = host.substring(0, 120);
        }
        byte[] suffix = new byte[6];
        new SecureRandom().nextBytes(suffix);
        StringBuilder hex = new StringBuilder(12);
        for (byte b : suffix) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return host + ":" + hex;
    }
}
