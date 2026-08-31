package com.synctool.service.converter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.synctool.model.DatabaseType;

/** Resolves the {@link SqlDialect} for a database type. */
@Component
public class SqlDialectFactory {

    private final List<SqlDialect> dialects;
    private final GenericSqlDialect fallback;

    public SqlDialectFactory(List<SqlDialect> dialects, GenericSqlDialect fallback) {
        this.dialects = dialects;
        this.fallback = fallback;
    }

    public SqlDialect forType(DatabaseType type) {
        if (type == null) {
            return fallback;
        }
        DatabaseType.DialectFamily family = type.getFamily();
        if (family == DatabaseType.DialectFamily.GENERIC) {
            return fallback;
        }
        for (SqlDialect dialect : dialects) {
            if (dialect.family() == family) {
                return dialect;
            }
        }
        return fallback;
    }
}
