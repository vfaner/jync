package com.qqmu.jync.service.metadata;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

/**
 * Metadata reader for the six analytic / search engines that take part in real-time sync as
 * polling sources: Doris, ClickHouse, Hive, Impala, Elasticsearch SQL and MongoDB Atlas SQL.
 *
 * <p>It reads tables exactly like the generic JDBC reader, with one difference: it never trusts
 * {@code getPrimaryKeys()}. These products have no enforced unique constraint — Doris and
 * ClickHouse report a duplicate/aggregate sort key, and the drivers of the others report
 * whatever their index layer calls a key. A keyset plan built on that reads inconsistently, and
 * treating it as a key would let the target collapse distinct rows. Tables are read as keyless
 * and the strategy runs as polling / full compare.
 */
@Component
public class KeylessEngineMetadataReader extends GenericMetadataReader {

    @Override
    public boolean supports(DatabaseType type) {
        return type == DatabaseType.DORIS || type == DatabaseType.CLICKHOUSE
                || type == DatabaseType.HIVE || type == DatabaseType.IMPALA
                || type == DatabaseType.ELASTICSEARCH || type == DatabaseType.MONGODB;
    }

    @Override
    protected boolean trustsDriverPrimaryKeys() {
        return false;
    }
}
