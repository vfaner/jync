package com.qqmu.jync.service.task;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.qqmu.jync.config.SyncProperties;

/**
 * How many sync cycles are running against each connection configuration.
 *
 * <p>Every cycle holds one connection per side for its whole run, and the full-load workers
 * borrow from the same pool, so the number of cycles on a configuration is what decides how
 * many spare seats its workers may split. Counting in <em>seats</em> rather than tasks keeps
 * one project whose source and target are the same configuration honest: it holds both sides
 * of that pool at once.
 *
 * <p>Two uses: {@link #tryEnter} admits at most half the pool's seats to running cycles
 * (the other half stays free for workers), and {@link #activeOn} feeds the per-cycle
 * parallelism calculation. Refusal is cheap — the caller defers the round without borrowing
 * anything — so a busy configuration queues at the scheduler instead of stalling mid-chunk.
 *
 * <p>The table is per JVM. Cycles on another instance are invisible here; the pool's own
 * counters and the deferral path still keep the database within its connection limit.
 */
@Component
public class PoolActivity {

    /** Seats each running cycle may hold, at most, on one configuration. */
    private final int maxSeats;

    private final Map<Long, Integer> activeSeats = new ConcurrentHashMap<>();

    @Autowired
    public PoolActivity(SyncProperties properties) {
        this(Math.max(2, properties.getMaxPoolSize()) / 2);
    }

    /** Visible for tests that want a pool size other than the configured one. */
    PoolActivity(int maxSeats) {
        this.maxSeats = Math.max(1, maxSeats);
    }

    /**
     * Admits one cycle, taking a seat on each of its two configurations (two on one when the
     * source and target are the same). Returns false — having taken nothing — when either
     * configuration is at its ceiling.
     */
    public synchronized boolean tryEnter(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) {
            // An unsaved endpoint has no pool to ration; the run is counted nowhere.
            return true;
        }
        boolean same = sourceId.equals(targetId);
        int seats = same ? 2 : 1;
        if (atCeiling(sourceId, seats) || (!same && atCeiling(targetId, seats))) {
            return false;
        }
        activeSeats.merge(sourceId, seats, Integer::sum);
        if (!same) {
            activeSeats.merge(targetId, seats, Integer::sum);
        }
        return true;
    }

    /** Releases the seats {@link #tryEnter} took; an unmatched call changes nothing. */
    public synchronized void leave(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) {
            return;
        }
        release(sourceId, 1);
        if (!sourceId.equals(targetId)) {
            release(targetId, 1);
        }
    }

    /** Seats currently held on a configuration; 0 when it is idle or unknown. */
    public int activeOn(Long configId) {
        if (configId == null) {
            return 0;
        }
        return activeSeats.getOrDefault(configId, 0);
    }

    private boolean atCeiling(Long configId, int seats) {
        return activeSeats.getOrDefault(configId, 0) + seats > maxSeats;
    }

    private void release(Long configId, int seats) {
        activeSeats.computeIfPresent(configId, (id, current) -> {
            int next = current - seats;
            return next > 0 ? next : null;
        });
    }
}
