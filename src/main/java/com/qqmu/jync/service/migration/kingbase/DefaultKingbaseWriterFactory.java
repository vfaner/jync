package com.qqmu.jync.service.migration.kingbase;

import java.sql.Connection;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.qqmu.jync.service.converter.PostgresDialect;

/**
 * KingbaseES shares the PostgreSQL dialect; its {@code CREATE TABLE} and
 * {@code INSERT ... ON CONFLICT} syntax are the ones this writer emits.
 */
@Component
public class DefaultKingbaseWriterFactory implements KingbaseWriterFactory {

    private final PostgresDialect dialect;

    /** OpenGaussDialect is also a PostgresDialect bean, so this one is named explicitly. */
    public DefaultKingbaseWriterFactory(@Qualifier("postgresDialect") PostgresDialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public KingbaseTableWriter open(Connection connection, String schema) {
        return new KingbaseTableWriter(connection, schema, dialect);
    }
}
