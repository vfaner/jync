package com.qqmu.jync.service.fanout;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.FanoutPosition;
import com.qqmu.jync.repository.FanoutPositionRepository;

/**
 * Per-target resume points for a fan-out project. The capture publishes to every target; each
 * target worker calls {@link #advance} only after its own commit, so a restart resumes each
 * target from the last position it actually wrote.
 */
@Service
public class FanoutPositionStore {

    private final FanoutPositionRepository repository;

    public FanoutPositionStore(FanoutPositionRepository repository) {
        this.repository = repository;
    }

    public Optional<FanoutPosition> find(Long projectId, Long targetDbId) {
        return repository.findByProjectIdAndTargetDbId(projectId, targetDbId);
    }

    public List<FanoutPosition> list(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    @Transactional
    public void advance(Long projectId, Long targetDbId, String file, long pos, String gtidSet,
                        String lsn, Long scn) {
        FanoutPosition position = repository.findByProjectIdAndTargetDbId(projectId, targetDbId)
                .orElseGet(() -> {
                    FanoutPosition created = new FanoutPosition();
                    created.setProjectId(projectId);
                    created.setTargetDbId(targetDbId);
                    return created;
                });
        if (file != null) {
            position.setBinlogFile(file);
            position.setBinlogPos(pos);
        }
        if (gtidSet != null) {
            position.setGtidSet(gtidSet);
        }
        if (lsn != null) {
            position.setLsn(lsn);
        }
        if (scn != null) {
            position.setScn(scn);
        }
        repository.save(position);
    }

    @Transactional
    public void delete(Long projectId, Long targetDbId) {
        repository.findByProjectIdAndTargetDbId(projectId, targetDbId)
                .ifPresent(repository::delete);
    }

    @Transactional
    public void deleteByProject(Long projectId) {
        repository.deleteByProjectId(projectId);
    }
}
