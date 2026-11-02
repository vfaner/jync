package com.qqmu.jync.dto;

import com.qqmu.jync.model.MigrationType;

import lombok.Getter;
import lombok.Setter;

/**
 * What the dedicated-migration task form may submit.
 *
 * <p>Type-specific options are not bound here: the controller collects {@code opt.*} request
 * parameters and hands them to the task's handler, which owns their shape.
 */
@Getter
@Setter
public class MigrationTaskForm {

    private Long id;
    private String name;
    private String description;
    private MigrationType type = MigrationType.ES_TO_EASYSEARCH;
    private Long sourceConnectionId;
    private Long targetConnectionId;
    private String cronExpression;
    private boolean enabled;
    private Integer timeoutSeconds;
}
