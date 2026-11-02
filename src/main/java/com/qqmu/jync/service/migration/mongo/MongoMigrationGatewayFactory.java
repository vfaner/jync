package com.qqmu.jync.service.migration.mongo;

import com.qqmu.jync.model.MigrationConnection;

/** Opens one gateway per migration connection; the caller closes it. */
public interface MongoMigrationGatewayFactory {

    MongoMigrationGateway open(MigrationConnection connection);
}
