package com.qqmu.jync.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether this process is running inside a container (Docker / Podman / Kubernetes).
 *
 * <p>Used only by the upgrade path: a container's {@code jync.jar} lives in an image layer and
 * is replaced by pulling a new image and recreating the container, not by swapping the file
 * in place. Detection is deliberately conservative and can be overridden with
 * {@code -Djync.container=true|false} or {@code JYN_CONTAINER=true|false} for unusual runtimes.
 */
public final class DeploymentMode {

    private DeploymentMode() {
    }

    public static boolean isContainer() {
        String override = System.getProperty("jync.container");
        if (override == null || override.isBlank()) {
            override = System.getenv("JYN_CONTAINER");
        }
        if (override != null && !override.isBlank()) {
            return "true".equalsIgnoreCase(override.trim());
        }
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null
                && !System.getenv("KUBERNETES_SERVICE_HOST").isBlank()) {
            return true;
        }
        String containerEnv = System.getenv("container");
        if (containerEnv != null && !containerEnv.isBlank()) {
            return true;   // podman / systemd-nspawn / some runtimes
        }
        return Files.exists(Path.of("/.dockerenv"));
    }
}
