package com.qqmu.jync.service.etl;

import com.qqmu.jync.service.converter.SqlDialect;

/**
 * The extraction SQL a table selected in the batch form starts with. Quoting comes from the
 * source's own dialect: an unquoted mixed-case Oracle name, or one containing a space, would
 * otherwise fail on the first run, long after the batch was created.
 */
final class EtlBatchTableSql {

    private EtlBatchTableSql() {
    }

    static String selectAll(SqlDialect dialect, String schema, String table) {
        return "select * from " + dialect.qualify(schema, table);
    }
}
