package com.qqmu.jync.service.converter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

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
        return forType(type, null);
    }

    /**
     * Variant configured with the connected target server's major version. Dialects whose SQL
     * does not vary by version return the same instance.
     */
    public SqlDialect forType(DatabaseType type, Integer targetMajorVersion) {
        if (type == null) {
            return fallback;
        }
        // OracleDialect also serves 达梦/崖山, whose version numbering is not Oracle's
        // (DM is "version 8" yet supports identity syntax) — the 11g gate is Oracle-only.
        Integer oracleVersion = type == DatabaseType.ORACLE ? targetMajorVersion : null;
        // Product-specific dialects win over their family's generic dialect (openGauss
        // diverges from plain PostgreSQL only on the upsert form).
        for (SqlDialect dialect : dialects) {
            if (type.equals(dialect.explicitType())) {
                return dialect.withServerMajorVersion(oracleVersion);
            }
        }
        DatabaseType.DialectFamily family = type.getFamily();
        if (family == DatabaseType.DialectFamily.GENERIC) {
            return fallback;
        }
        for (SqlDialect dialect : dialects) {
            if (dialect.explicitType() == null && dialect.family() == family) {
                return dialect.withServerMajorVersion(oracleVersion);
            }
        }
        return fallback;
    }
}
