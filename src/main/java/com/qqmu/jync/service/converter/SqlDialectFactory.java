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
        // OracleDialect also serves 达梦/崖山, whose identity syntax and version numbering
        // are not Oracle's: DM's documented grammar is IDENTITY(seed,increment) and an
        // explicit PK insert into such a column needs a per-session IDENTITY_INSERT switch,
        // which the sync cannot do — so non-Oracle Oracle-family products always get the
        // universally-valid sequence+trigger emulation (version 11 branch of the dialect).
        Integer oracleVersion = oracleDialectVersion(type, targetMajorVersion);
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

    /**
     * Version handed to the Oracle dialect: the connected server's effective version for the
     * real product ({@code null} when it could not be resolved — the dialect then chooses the
     * portable emulation), and a fixed pre-12 marker for other Oracle-family products.
     */
    private static Integer oracleDialectVersion(DatabaseType type, Integer targetMajorVersion) {
        if (type == DatabaseType.ORACLE) {
            return targetMajorVersion;
        }
        return type.getFamily() == DatabaseType.DialectFamily.ORACLE ? 11 : null;
    }
}
