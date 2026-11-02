package com.qqmu.jync.service.migration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.MigrationType;

/**
 * The single lookup from a stored task's type to the handler that can execute it.
 *
 * <p>Construction fails when two handlers claim one type, or when a handler claims a type the
 * enum still marks as unavailable. Both are wiring mistakes that should stop the application
 * rather than surface as a task that can be saved but never run.
 */
@Component
public class MigrationTypeRegistry {

    private final Map<MigrationType, MigrationTypeHandler> handlers;

    public MigrationTypeRegistry(List<MigrationTypeHandler> handlerList) {
        Map<MigrationType, MigrationTypeHandler> map = new EnumMap<>(MigrationType.class);
        for (MigrationTypeHandler handler : handlerList) {
            MigrationType type = handler.type();
            if (!type.available()) {
                throw new IllegalStateException(
                        "Migration type " + type + " is not available but has a handler");
            }
            if (map.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException("Duplicate migration handler for " + type);
            }
        }
        this.handlers = Map.copyOf(map);
    }

    public Optional<MigrationTypeHandler> find(MigrationType type) {
        return Optional.ofNullable(handlers.get(type));
    }

    public boolean isRegistered(MigrationType type) {
        return handlers.containsKey(type);
    }
}
