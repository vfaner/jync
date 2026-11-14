package com.qqmu.jync.service.etl;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.EtlRunLock;
import com.qqmu.jync.repository.EtlRunLockRepository;

/**
 * {@link EtlLeaseStore} over the metadata database.
 *
 * <p>A separate bean from {@link EtlExecutionGuard} because {@code @Transactional} works through
 * the proxy: called from the guard's own methods, the {@code @Modifying} statements would run
 * without a transaction of their own and a rolled-back caller could take the lease with it. The
 * write operations also need {@code REQUIRES_NEW}: the guard can be reached from inside a
 * transactional caller (task deletion, batch membership edits), and a lease must commit even if
 * that caller's transaction later rolls back.
 */
@Service
public class JpaEtlLeaseStore implements EtlLeaseStore {

    private final EtlRunLockRepository repository;

    public JpaEtlLeaseStore(EtlRunLockRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String key) {
        return repository.existsById(key);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(String key, String owner, long ttlMs) {
        EtlRunLock lock = new EtlRunLock();
        lock.setLockKey(key);
        lock.setOwnerId(owner);
        lock.setExpiresAt(Instant.now().plusMillis(ttlMs));
        // saveAndFlush, not save: the duplicate-key violation must surface here, inside this
        // transaction, rather than at some later flush in the caller's code.
        repository.saveAndFlush(lock);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String key, String owner, long ttlMs) {
        Instant now = Instant.now();
        return repository.claim(key, owner, now.plusMillis(ttlMs), now) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renew(String key, String owner, long ttlMs) {
        return repository.renew(key, owner, Instant.now().plusMillis(ttlMs)) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key, String owner) {
        repository.release(key, owner);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discard(String key) {
        repository.discard(key);
    }
}
