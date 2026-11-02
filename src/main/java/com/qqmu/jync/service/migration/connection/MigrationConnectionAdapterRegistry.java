package com.qqmu.jync.service.migration.connection;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.MigrationConnectionKind;

/** Resolves the adapter that owns a connection kind. Spring discovers the adapters. */
@Component
public class MigrationConnectionAdapterRegistry {

    private final Map<MigrationConnectionKind, MigrationConnectionKindAdapter> adapters;

    public MigrationConnectionAdapterRegistry(List<MigrationConnectionKindAdapter> candidates) {
        Map<MigrationConnectionKind, MigrationConnectionKindAdapter> resolved =
                new EnumMap<>(MigrationConnectionKind.class);
        for (MigrationConnectionKindAdapter adapter : candidates) {
            for (MigrationConnectionKind kind : adapter.kinds()) {
                if (kind == null) {
                    throw new IllegalStateException(
                            "A migration connection adapter declared a null kind");
                }
                if (resolved.put(kind, adapter) != null) {
                    throw new IllegalStateException(
                            "Duplicate migration connection adapter for " + kind);
                }
            }
        }
        this.adapters = resolved;
    }

    public MigrationConnectionKindAdapter forKind(MigrationConnectionKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("error.migration.connection.kind.required");
        }
        MigrationConnectionKindAdapter adapter = adapters.get(kind);
        if (adapter == null) {
            throw new IllegalArgumentException("error.migration.connection.kind.unsupported");
        }
        return adapter;
    }

    /** Every kind an adapter has been registered for; used by startup diagnostics and tests. */
    public Set<MigrationConnectionKind> kinds() {
        return Set.copyOf(adapters.keySet());
    }
}
