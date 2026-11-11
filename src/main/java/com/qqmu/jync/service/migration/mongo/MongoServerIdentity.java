package com.qqmu.jync.service.migration.mongo;

import java.util.Objects;

/**
 * What a server says about itself, so two connections can be told apart by the server they
 * reached rather than by how their URIs spell the host.
 *
 * <p>{@code processId} comes from the {@code topologyVersion} the server includes in
 * {@code hello}; it identifies one server process, so it stays the same whether a connection
 * was opened through {@code localhost}, {@code 127.0.0.1} or a seed list. Servers old enough to
 * lack it fall back to the advertised host name ({@code me}) and the replica-set name.
 */
public record MongoServerIdentity(String database, String replicaSet, String processId,
                                  String me) {

    /** True when both records describe the same database on the same server process. */
    public boolean sameDatabaseAs(MongoServerIdentity other) {
        if (other == null || database == null || !database.equals(other.database)) {
            return false;
        }
        if (processId != null && other.processId != null) {
            return processId.equals(other.processId);
        }
        return me != null && me.equals(other.me) && Objects.equals(replicaSet, other.replicaSet);
    }
}
