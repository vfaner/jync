package com.qqmu.jync.service.migration.kingbase;

import java.sql.Connection;

/** Opens a table writer bound to one target connection and schema. */
public interface KingbaseWriterFactory {

    KingbaseTableWriter open(Connection connection, String schema);
}
