package com.qqmu.jync.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.repository.ChangeLogRepository;

/**
 * Read and maintenance operations over the change log.
 *
 * <p>The write path lives here rather than on the controller so the transaction boundary
 * sits with the rest of the application's writes, in the service layer.
 */
@Service
public class ChangeLogService {

    private final ChangeLogRepository repository;

    public ChangeLogService(ChangeLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public long count(Long projectId) {
        return projectId != null ? repository.countByProjectId(projectId) : repository.count();
    }

    @Transactional(readOnly = true)
    public Page<ChangeLog> page(Long projectId, Pageable pageable) {
        return projectId != null
                ? repository.findByProjectIdOrderByOccurredAtDesc(projectId, pageable)
                : repository.findAllByOrderByOccurredAtDesc(pageable);
    }

    /**
     * Clears change-log entries. With a project id only that project's logs are removed;
     * with null the entire audit trail is wiped.
     */
    @Transactional
    public void clear(Long projectId) {
        if (projectId != null) {
            repository.deleteByProjectId(projectId);
        } else {
            repository.deleteAllBulk();
        }
    }
}
