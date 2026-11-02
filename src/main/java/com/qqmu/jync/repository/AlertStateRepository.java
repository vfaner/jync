package com.qqmu.jync.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertState;

/** One row per (category, project, object), written by whichever node evaluates first. */
public interface AlertStateRepository extends JpaRepository<AlertState, Long> {

    Optional<AlertState> findByCategoryAndProjectIdAndObjectKey(AlertCategory category,
                                                                Long projectId,
                                                                String objectKey);

    /**
     * Removes every alert state owned by a project. Connection-wide rows store {@code 0}, so
     * a positive project id never touches them.
     */
    void deleteByProjectId(Long projectId);
}
