package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.qqmu.jync.service.cdc.YStreamServiceAdmin.Service;

/** {@link YStreamServiceOps} over one source connection. */
final class JdbcYStreamServiceOps implements YStreamServiceOps {

    private final Connection conn;

    JdbcYStreamServiceOps(Connection conn) {
        this.conn = conn;
    }

    @Override
    public List<Service> services(long projectId) throws SQLException {
        return YStreamServiceAdmin.servicesFor(conn, projectId);
    }

    @Override
    public void create(String serviceName) throws SQLException {
        YStreamServiceAdmin.createService(conn, serviceName);
    }

    @Override
    public void addTables(String serviceName, List<String> qualifiedTables) throws SQLException {
        YStreamServiceAdmin.addTables(conn, serviceName, qualifiedTables);
    }

    @Override
    public void start(String serviceName) throws SQLException {
        YStreamServiceAdmin.startService(conn, serviceName);
    }

    @Override
    public void stop(String serviceName) throws SQLException {
        YStreamServiceAdmin.stopService(conn, serviceName);
    }

    @Override
    public void drop(Service service) throws SQLException {
        YStreamServiceAdmin.dropService(conn, service);
    }

    @Override
    public Set<String> captured(String serviceName) throws SQLException {
        return YStreamServiceAdmin.capturedTables(conn, serviceName);
    }
}
