package com.qqmu.jync.service.cdc;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.qqmu.jync.service.cdc.YStreamServiceAdmin.Service;

/**
 * The source-side operations {@link YStreamServiceLifecycle} needs.
 *
 * <p>Kept as a port so the startup decision table — the part that decides whether a stored
 * position may be resumed — is unit-testable without a YashanDB instance; the production
 * implementation is a thin {@link java.sql.Connection} wrapper ({@link JdbcYStreamServiceOps}).
 */
public interface YStreamServiceOps {

    /** Every service this project owns on the instance. */
    List<Service> services(long projectId) throws SQLException;

    void create(String serviceName) throws SQLException;

    /** Adds {@code SCHEMA.TABLE} entries in dictionary spelling; never removes any. */
    void addTables(String serviceName, List<String> qualifiedTables) throws SQLException;

    void start(String serviceName) throws SQLException;

    void stop(String serviceName) throws SQLException;

    /** Stops (when started) and drops the service. */
    void drop(Service service) throws SQLException;

    /** The tables currently in the service's capture scope, as {@code SCHEMA.TABLE}. */
    Set<String> captured(String serviceName) throws SQLException;
}
