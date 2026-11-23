package com.qqmu.jync.service.converter;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

/**
 * Vastbase (海量数据库 G100) dialect.
 *
 * <p>Vastbase parses PostgreSQL's {@code INSERT … ON CONFLICT … DO UPDATE} but rejects the
 * trailing {@code WHERE} Jync adds to make an unchanged-row replay report zero affected
 * rows: a user report showed every batch failing on that clause when syncing to Vastbase
 * ("插入数据带着 where 条件，不支持"). The native statement therefore keeps the form the
 * server already accepted and drops the guard.
 *
 * <p>Everything else stays inherited from {@link PostgresDialect} — identifiers, type
 * mapping and metadata. The unchanged-row guard cannot be expressed on the native
 * statement, so the engine emulates guarded incremental windows as a guarded UPDATE plus
 * an INSERT; bulk loads keep the single statement.
 */
@Component
public class VastbaseDialect extends PostgresDialect {

    @Override
    public DatabaseType explicitType() {
        return DatabaseType.VASTBASE;
    }

    @Override
    public boolean supportsUpsertGuard() {
        return false;
    }

    @Override
    public boolean emulatesUpsertGuard() {
        return true;
    }

    /**
     * The emulated UPDATE uses plain {@code ?} markers, so a MySQL wall-clock literal must
     * be parsed before binding rather than relying on a PostgreSQL cast.
     */
    @Override
    public boolean requiresTypedTemporalBind() {
        return true;
    }
}
