package com.qqmu.jync.service.converter;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

/**
 * Huawei GaussDB dialect.
 *
 * <p>GaussDB is a separate product from openGauss (different driver and URL), but its SQL
 * is PostgreSQL-derived and its parser takes the same MySQL-flavoured upsert: Huawei's
 * reference documents {@code INSERT … ON DUPLICATE KEY UPDATE}, with the caveat that key
 * columns cannot be updated — {@link OpenGaussDialect} only assigns non-key columns, so the
 * form stays within that limit. Everything else is inherited from the PostgreSQL family.
 */
@Component
public class GaussDbDialect extends OpenGaussDialect {

    @Override
    public DatabaseType explicitType() {
        return DatabaseType.GAUSSDB;
    }
}
