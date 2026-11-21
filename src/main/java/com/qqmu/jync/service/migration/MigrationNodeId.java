package com.qqmu.jync.service.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Identifies this deployment instance, so that startup recovery can tell "a run my own
 * process left RUNNING" (interrupted) from "a run another live node is executing right now"
 * (leave alone).
 *
 * <p>The id must survive a restart on the same node — a per-JVM random value would make every
 * crashed run look like another node's and leave it RUNNING forever. It is therefore persisted
 * in the application data directory (the same sidecar idea as {@code data/jync.key}), and can
 * be pinned explicitly with {@code jync.node-id} when several nodes share a data volume.
 */
@Component
public class MigrationNodeId {

    static final String DEFAULT_FILE = "./data/jync.node-id";

    private final String id;

    @Autowired
    public MigrationNodeId(@Value("${jync.node-id:}") String configured,
                           @Value("${jync.node-id-file:" + DEFAULT_FILE + "}") String file) {
        this.id = resolve(configured, Path.of(file));
    }

    /** Test seam: an explicit id without touching the filesystem. */
    MigrationNodeId(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    private static String resolve(String configured, Path file) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            if (Files.isRegularFile(file)) {
                String stored = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!stored.isEmpty()) {
                    return stored;
                }
            }
            String generated = UUID.randomUUID().toString();
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, generated, StandardCharsets.UTF_8);
            return generated;
        } catch (IOException | RuntimeException e) {
            // An unwritable data directory must not stop the application: fall back to an id
            // that is stable only for this process (the pre-multi-node behaviour).
            return UUID.randomUUID().toString();
        }
    }
}
