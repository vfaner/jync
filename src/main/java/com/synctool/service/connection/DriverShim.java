package com.synctool.service.connection;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Delegating wrapper that lets {@link java.sql.DriverManager} use a driver loaded by a
 * non-system {@link ClassLoader}.
 *
 * <p>{@code DriverManager} refuses to use a driver class it cannot see from the caller's
 * classloader, which is exactly the case for a jar loaded at runtime. Registering this
 * shim — which lives on the system classloader — sidesteps that check.
 */
public class DriverShim implements Driver {

    private final Driver delegate;

    public DriverShim(Driver delegate) {
        this.delegate = delegate;
    }

    public Driver getDelegate() {
        return delegate;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return delegate.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return delegate.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public String toString() {
        return "DriverShim[" + delegate.getClass().getName() + "]";
    }
}
