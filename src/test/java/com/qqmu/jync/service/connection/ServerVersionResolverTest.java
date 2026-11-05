package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * MySQL 5.7 and 8.0 accept different SQL (window functions, expression defaults). The major
 * version comes from the server itself, never from the preset the operator picked — a
 * "MySQL" connection may point at either release.
 */
class ServerVersionResolverTest {

    private DataSourceManager dataSourceManager;
    private ServerVersionResolver resolver;
    private Connection connection;
    private DatabaseMetaData metaData;

    @BeforeEach
    void setUp() throws SQLException {
        dataSourceManager = mock(DataSourceManager.class);
        resolver = new ServerVersionResolver(dataSourceManager);
        connection = mock(Connection.class);
        metaData = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
    }

    @Test
    void mysqlMajorVersionComesFromTheServer() throws SQLException {
        when(metaData.getDatabaseMajorVersion()).thenReturn(5);

        assertThat(resolver.majorVersion(config(DatabaseType.MYSQL, 1L))).isEqualTo(5);
    }

    @Test
    void mariaDbIsResolvedOnItsOwnReleaseTrain() throws SQLException {
        // MariaDB numbers 10/11 where MySQL numbers 5/8; the caller judges each release train
        // separately, so the number must come through untouched.
        when(metaData.getDatabaseMajorVersion()).thenReturn(10);

        assertThat(resolver.majorVersion(config(DatabaseType.MARIADB, 1L))).isEqualTo(10);
    }

    @Test
    void otherProductsAreNotProbed() throws SQLException {
        // PostgreSQL, Oracle and the MySQL-protocol forks (Doris, TiDB...) keep their existing
        // attempt-then-fall-back behavior; resolving a version for them would change SQL they
        // are already handling.
        assertThat(resolver.majorVersion(config(DatabaseType.ORACLE, 1L))).isNull();
        assertThat(resolver.majorVersion(config(DatabaseType.DORIS, 1L))).isNull();
        verify(dataSourceManager, never()).getConnection(any());
    }

    @Test
    void anEmptyVersionReportIsUnknown() throws SQLException {
        when(metaData.getDatabaseMajorVersion()).thenReturn(0);

        assertThat(resolver.majorVersion(config(DatabaseType.MYSQL, 1L))).isNull();
    }

    @Test
    void anUnreachableServerIsNotRememberedAsUnknown() throws SQLException {
        // A failed connection is transient: the next cycle must retry instead of treating the
        // server as "version unknown" for the rest of the process's life.
        DatabaseConfig config = config(DatabaseType.MYSQL, 1L);
        when(dataSourceManager.getConnection(any())).thenThrow(new SQLException("refused"));
        assertThat(resolver.majorVersion(config)).isNull();

        doReturn(connection).when(dataSourceManager).getConnection(any());
        when(metaData.getDatabaseMajorVersion()).thenReturn(8);
        assertThat(resolver.majorVersion(config)).isEqualTo(8);
    }

    @Test
    void aResolvedVersionIsCachedForAnUnchangedConfig() throws SQLException {
        when(metaData.getDatabaseMajorVersion()).thenReturn(5);
        DatabaseConfig config = config(DatabaseType.MYSQL, 1L);

        resolver.majorVersion(config);
        resolver.majorVersion(config);

        verify(dataSourceManager, times(1)).getConnection(any());
    }

    @Test
    void editingTheConnectionProbesAgain() throws SQLException {
        // The edit may repoint the config at another server, so the cached answer dies with
        // the old revision.
        DatabaseConfig config = config(DatabaseType.MYSQL, 1L);
        when(metaData.getDatabaseMajorVersion()).thenReturn(5);
        assertThat(resolver.majorVersion(config)).isEqualTo(5);

        config.setUpdatedAt(Instant.now().plusSeconds(60));
        when(metaData.getDatabaseMajorVersion()).thenReturn(8);

        assertThat(resolver.majorVersion(config)).isEqualTo(8);
        verify(dataSourceManager, times(2)).getConnection(any());
    }

    @Test
    void aSaturatedPoolIsNotBorrowedForAVersionHint() throws SQLException {
        // The hint is not worth a fifteen-second Hikari timeout, and not worth a seat that a
        // running chunk worker needs. Unknown is a safe answer: the caller attempts the
        // sampling query and falls back, exactly as before this feature.
        DatabaseConfig config = config(DatabaseType.MYSQL, 1L);
        when(dataSourceManager.isPoolSaturated(config)).thenReturn(true);

        assertThat(resolver.majorVersion(config)).isNull();
        verify(dataSourceManager, never()).getConnection(any());
    }

    @Test
    void aNullConfigIsUnknown() {
        assertThat(resolver.majorVersion(null)).isNull();
    }

    private static DatabaseConfig config(DatabaseType type, Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName("orders-" + id);
        config.setType(type);
        config.setUpdatedAt(Instant.parse("2026-11-01T00:00:00Z"));
        return config;
    }
}
