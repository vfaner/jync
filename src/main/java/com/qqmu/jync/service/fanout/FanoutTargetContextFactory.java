package com.qqmu.jync.service.fanout;

import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Service;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.task.SyncContextFactory;

/**
 * Builds one {@link SyncContext} per fan-out target. The project id and sync config stay the
 * same (logs, selection and rules are shared); only {@code targetDbId} changes, which is what
 * the context factory uses to resolve that target's connection, schema and dialect.
 */
@Service
public class FanoutTargetContextFactory {

    private final SyncContextFactory contextFactory;

    public FanoutTargetContextFactory(SyncContextFactory contextFactory) {
        this.contextFactory = contextFactory;
    }

    public SyncContext build(Project project, Long targetDbId, String lockOwner,
                             BooleanSupplier lockRenewer) {
        Project clone = new Project();
        clone.setId(project.getId());
        clone.setName(project.getName());
        clone.setDescription(project.getDescription());
        clone.setSourceDbId(project.getSourceDbId());
        clone.setTargetDbId(targetDbId);
        clone.setTargetKind(SyncTargetKind.JDBC);
        clone.setSyncConfig(project.getSyncConfig());
        clone.setOwnerUsername(project.getOwnerUsername());
        return contextFactory.build(clone, lockOwner, lockRenewer);
    }
}
