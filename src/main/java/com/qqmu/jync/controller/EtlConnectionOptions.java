package com.qqmu.jync.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.auth.AccessGuard;

/**
 * The source/target split shared by the ETL task form and the ETL batch form.
 *
 * <p>A connection classified as a source is not offered as a target and the other way around.
 * A saved pick is still appended when its role no longer matches — a reclassified connection
 * must render the stored value instead of silently falling back to a different one.
 */
final class EtlConnectionOptions {

    private EtlConnectionOptions() {
    }

    static List<DatabaseConfig> sources(List<DatabaseConfig> connections, Long selectedId) {
        return sources(connections, selectedId, null);
    }

    static List<DatabaseConfig> sources(List<DatabaseConfig> connections, Long selectedId,
                                        String scope) {
        List<DatabaseConfig> options = connections.stream()
                .filter(c -> inScope(c, scope))
                .filter(c -> roleOf(c) == ConnectionRole.SOURCE)
                .collect(Collectors.toCollection(ArrayList::new));
        // Appended from the full list: a saved pick outside the scope still renders, or the
        // edit form would silently swap the connection for whichever option comes first.
        appendSelected(connections, options, selectedId);
        return options;
    }

    /**
     * Connections a task or batch may write to: classified as a target and able to take a write.
     * Elasticsearch and MongoDB expose a read-only SQL interface, so they stay in the source
     * list only. A saved pick still shows its current target — the save path refuses it with a
     * message, but hiding the value would make the error impossible to understand.
     */
    static List<DatabaseConfig> targets(List<DatabaseConfig> connections, Long selectedId) {
        return targets(connections, selectedId, null);
    }

    static List<DatabaseConfig> targets(List<DatabaseConfig> connections, Long selectedId,
                                        String scope) {
        List<DatabaseConfig> options = connections.stream()
                .filter(c -> inScope(c, scope))
                .filter(c -> roleOf(c) == ConnectionRole.TARGET)
                .filter(DatabaseConfig::supportsEtlTarget)
                .collect(Collectors.toCollection(ArrayList::new));
        appendSelected(connections, options, selectedId);
        return options;
    }

    /**
     * The connection scope for a form: the owner of the resource being edited, otherwise the
     * caller. An administrator gets everything only when neither applies.
     */
    static String optionScope(String resourceOwner, AccessGuard access) {
        if (resourceOwner != null) {
            return resourceOwner;
        }
        return access.isAdmin() ? null : access.username();
    }

    private static boolean inScope(DatabaseConfig config, String scope) {
        return scope == null || scope.equals(config.getOwnerUsername());
    }

    /** Rows created before the role column existed are treated as sources, like the backfill. */
    static ConnectionRole roleOf(DatabaseConfig config) {
        return config.getRole() == null ? ConnectionRole.SOURCE : config.getRole();
    }

    private static void appendSelected(List<DatabaseConfig> connections,
                                       List<DatabaseConfig> options, Long selectedId) {
        if (selectedId != null && options.stream().noneMatch(c -> selectedId.equals(c.getId()))) {
            connections.stream()
                    .filter(c -> selectedId.equals(c.getId()))
                    .findFirst()
                    .ifPresent(options::add);
        }
    }
}
