package com.qqmu.jync.service.migration.connection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.util.CryptoUtil;

/** Opens JDBC connections to a KingbaseES target; credentials only ever come from the form. */
@Component
public class KingbaseConnectionFactory {

    private final CryptoUtil cryptoUtil;

    public KingbaseConnectionFactory(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    /** The effective JDBC URL: the stored base URL plus any driver parameters. */
    public String url(MigrationConnection connection) {
        String base = connection.getBaseUrl() == null ? "" : connection.getBaseUrl().trim();
        String extra = stripSeparators(connection.getExtraParams());
        if (extra == null) {
            return base;
        }
        while (base.endsWith("?") || base.endsWith("&")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + (base.indexOf('?') >= 0 ? '&' : '?') + extra;
    }

    /** Users paste parameter lists with a leading {@code ?} or {@code &}; it is not part of it. */
    private static String stripSeparators(String extraParams) {
        if (extraParams == null) {
            return null;
        }
        String extra = extraParams.trim();
        while (extra.startsWith("?") || extra.startsWith("&")) {
            extra = extra.substring(1);
        }
        return extra.isEmpty() ? null : extra;
    }

    public Connection open(MigrationConnection connection) throws SQLException {
        return open(connection, cryptoUtil.decrypt(connection.getPasswordEnc()));
    }

    public Connection open(MigrationConnection connection, String plainPassword)
            throws SQLException {
        return DriverManager.getConnection(url(connection), connection.getUsername(),
                plainPassword);
    }
}
