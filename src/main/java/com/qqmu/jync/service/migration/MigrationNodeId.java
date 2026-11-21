package com.qqmu.jync.service.migration;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Identifies this JVM instance, so a restart can tell "a run my previous process left
 * RUNNING" (interrupted) from "a run another node is executing right now" (leave alone).
 * A random id per start is enough: only equality with a row's recorded owner matters.
 */
@Component
public class MigrationNodeId {

    private final String id = UUID.randomUUID().toString();

    public String id() {
        return id;
    }
}
