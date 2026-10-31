package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * Listing one saved connection's tables is what backs the ETL form's target-table picker. The
 * connection is dialled lazily, once, and every failure has to reach the browser as a message
 * key — the form still accepts a hand-typed table name, so a failed listing is a hint, not an
 * error page.
 */
class ConnectionCatalogServiceTest {

    private final DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private final MetadataReaderFactory readerFactory = mock(MetadataReaderFactory.class);
    private final MetadataReader reader = mock(MetadataReader.class);

    private final ConnectionCatalogService service =
            new ConnectionCatalogService(repository, dataSourceManager, readerFactory);

    @BeforeEach
    void setUp() {
        when(readerFactory.forType(any(), any())).thenReturn(reader);
    }

    @Test
    void listsTheTablesOfTheConfiguredSchema() throws Exception {
        DatabaseConfig config = config(DatabaseType.MYSQL, "appdb");
        Connection connection = mock(Connection.class);
        when(repository.findById(9L)).thenReturn(Optional.of(config));
        when(dataSourceManager.getConnection(config)).thenReturn(connection);
        when(reader.listTableNames(connection, "appdb")).thenReturn(List.of("orders", "users"));

        ConnectionCatalogService.Tables tables = service.listTables(9L);

        assertThat(tables.schema()).isEqualTo("appdb");
        assertThat(tables.tables()).containsExactly("orders", "users");
        // The connection is borrowed for the listing only.
        verify(connection).close();
    }

    @Test
    void fallsBackToTheConnectionsDefaultSchemaWhenNoneIsConfigured() throws Exception {
        DatabaseConfig config = config(DatabaseType.POSTGRESQL, "  ");
        Connection connection = mock(Connection.class);
        when(repository.findById(9L)).thenReturn(Optional.of(config));
        when(dataSourceManager.getConnection(config)).thenReturn(connection);
        when(reader.resolveDefaultSchema(connection)).thenReturn("public");
        when(reader.listTableNames(connection, "public")).thenReturn(List.of("customers"));

        ConnectionCatalogService.Tables tables = service.listTables(9L);

        assertThat(tables.schema()).isEqualTo("public");
        assertThat(tables.tables()).containsExactly("customers");
    }

    @Test
    void anUnknownConnectionIsRefusedBeforeAnyDial() throws Exception {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listTables(404L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.connection.missing");
        verify(dataSourceManager, never()).getConnection(any());
    }

    /** Native MongoDB has no JDBC connection; opening one would NPE inside the pool. */
    @Test
    void aNativeMongoConnectionIsRefusedBeforeAnyDial() throws Exception {
        DatabaseConfig config = config(DatabaseType.MONGODB_NATIVE, "shop");
        when(repository.findById(9L)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.listTables(9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.jdbcUnsupported");
        verify(dataSourceManager, never()).getConnection(any());
    }

    @Test
    void aDeadDatabaseBecomesAPresentableKey() throws Exception {
        DatabaseConfig config = config(DatabaseType.MYSQL, "appdb");
        when(repository.findById(9L)).thenReturn(Optional.of(config));
        when(dataSourceManager.getConnection(config))
                .thenThrow(new SQLException("connection refused"));

        assertThatThrownBy(() -> service.listTables(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.target.tables.read.failed");
    }

    private static DatabaseConfig config(DatabaseType type, String schema) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(9L);
        config.setName("target");
        config.setType(type);
        config.setSchemaName(schema);
        return config;
    }
}
