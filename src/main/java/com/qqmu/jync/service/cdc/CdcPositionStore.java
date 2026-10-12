package com.qqmu.jync.service.cdc;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.repository.CdcPositionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Transactional gateway for {@link CdcPosition}, mirroring the SyncLockStore pattern:
 * a separate bean so REQUIRES_NEW actually proxies, letting the CDC consumer persist
 * positions from its own long-running thread without an ambient transaction.
 */
@Service
@Slf4j
public class CdcPositionStore {

    private final CdcPositionRepository repository;

    public CdcPositionStore(CdcPositionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<CdcPosition> find(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    /** Upsert the start-of-baseline position; baselineDone resets to false. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CdcPosition recordBaselineStart(Long projectId, String file, long pos,
                                           String gtidSet, long serverId) {
        CdcPosition position = repository.findByProjectId(projectId).orElseGet(() -> {
            CdcPosition created = new CdcPosition();
            created.setProjectId(projectId);
            return created;
        });
        position.setBinlogFile(file);
        position.setBinlogPos(pos);
        position.setGtidSet(gtidSet);
        position.setServerId(serverId);
        position.setBaselineDone(false);
        position.setUpdatedAt(Instant.now());
        return repository.save(position);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBaselineDone(Long projectId) {
        repository.findByProjectId(projectId).ifPresent(position -> {
            position.setBaselineDone(true);
            position.setUpdatedAt(Instant.now());
            repository.save(position);
        });
    }

    /**
     * Post-commit position advance. Ignored when the row vanished underneath us — the
     * pinned "no exception" semantics stay (the transaction is already committed on the
     * target and replays from whatever position survives) — but no longer SILENTLY (final
     * review FIX 1): a holder advancing against a deleted row is the visible symptom of a
     * cross-node position kill, so it earns a WARN naming the project.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advance(Long projectId, String file, long pos, String gtidSet) {
        Optional<CdcPosition> existing = repository.findByProjectId(projectId);
        if (!existing.isPresent()) {
            log.warn("CDC position for project {} not advanced to {}:{}: no position row"
                    + " (deleted underneath the consumer?); the transaction replays after"
                    + " the next reconnect", projectId, file, pos);
            return;
        }
        CdcPosition position = existing.get();
        position.setBinlogFile(file);
        position.setBinlogPos(pos);
        if (gtidSet != null) {
            position.setGtidSet(gtidSet);
        }
        position.setUpdatedAt(Instant.now());
        repository.save(position);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(Long projectId) {
        repository.deleteByProjectId(projectId);
    }

    /**
     * LogMiner variant of {@link #recordBaselineStart}: captures the current SCN as the
     * baseline anchor (the mining session later resumes from {@code CSCN > this scn}).
     * Mirrors the binlog contract: baselineDone resets to false; other columns left alone.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CdcPosition recordBaselineStartScn(Long projectId, long scn) {
        CdcPosition position = repository.findByProjectId(projectId).orElseGet(() -> {
            CdcPosition created = new CdcPosition();
            created.setProjectId(projectId);
            return created;
        });
        position.setScn(scn);
        position.setBaselineDone(false);
        position.setUpdatedAt(Instant.now());
        return repository.save(position);
    }

    /**
     * LogMiner variant of {@link #advance}: post-commit SCN advance with the same
     * "row vanished → WARN, never throw" semantics (the transaction already committed on
     * the target; the next mining session resumes from whatever survives).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advanceScn(Long projectId, long scn) {
        Optional<CdcPosition> existing = repository.findByProjectId(projectId);
        if (!existing.isPresent()) {
            log.warn("CDC position for project {} not advanced to SCN {}: no position row"
                    + " (deleted underneath the consumer?); the transaction replays after"
                    + " the next reconnect", projectId, scn);
            return;
        }
        CdcPosition position = existing.get();
        position.setScn(scn);
        position.setUpdatedAt(Instant.now());
        repository.save(position);
    }
}
