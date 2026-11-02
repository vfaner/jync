package com.qqmu.jync.model;

/** What a dedicated-migration endpoint actually is. */
public enum MigrationConnectionKind {
    ELASTICSEARCH,
    OPENSEARCH,
    EASYSEARCH,
    /** Native MongoDB (the bundled {@code mongodb-driver-sync}), never the Atlas SQL JDBC preset. */
    MONGODB,
    KINGBASE
}
