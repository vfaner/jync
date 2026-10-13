package com.qqmu.jync.service.converter;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

/**
 * openGauss dialect.
 *
 * <p>Shares PostgreSQL syntax for identifiers, functions and type mapping, but the upsert
 * diverges: a default openGauss 5.0 instance runs in {@code sql_compatibility='A'}
 * (MySQL-compatible, the factory default of the official image), whose parser rejects
 * {@code INSERT … ON CONFLICT} ("syntax error at or near CONFLICT") while accepting the
 * MySQL-flavoured {@code INSERT … ON DUPLICATE KEY UPDATE}. Verified against a real
 * opengauss/opengauss:5.0.0 container in the v1.7.1 docker test matrix — emitting the
 * PostgreSQL form made every baseline batch fail and parked the project in ERROR.
 * Everything else stays inherited from {@link PostgresDialect}, including the
 * {@code ?::time} placeholder cast TIME binds need.
 */
@Component
public class OpenGaussDialect extends PostgresDialect {

    @Override
    public DatabaseType explicitType() {
        return DatabaseType.OPENGAUSS;
    }

    @Override
    protected String upsertSuffix(List<String> columns, List<String> pkColumns) {
        List<String> updatable = nonKeyColumns(columns, pkColumns);
        if (updatable.isEmpty()) {
            // Key-only table: a self-assignment keeps the statement an idempotent insert.
            String anyCol = quoteIdentifier(columns.get(0));
            return " ON DUPLICATE KEY UPDATE " + anyCol + " = " + anyCol;
        }
        String updates = updatable.stream()
                .map(c -> quoteIdentifier(c) + " = VALUES(" + quoteIdentifier(c) + ")")
                .collect(Collectors.joining(", "));
        return " ON DUPLICATE KEY UPDATE " + updates;
    }
}
