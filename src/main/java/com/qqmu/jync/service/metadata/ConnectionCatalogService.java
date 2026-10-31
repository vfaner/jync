package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * Lists the tables of one saved connection for a UI picker.
 *
 * <p>Unlike a project, which owns a source connection, an ETL task picks its target at form
 * time; the form therefore needs a connection-addressed lookup. The listing is advisory: it
 * dials the live database, so callers must treat a failure as "type the name yourself", not as
 * an error page.
 */
@Service
public class ConnectionCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionCatalogService.class);

    private final DatabaseConfigRepository repository;
    private final DataSourceManager dataSourceManager;
    private final MetadataReaderFactory readerFactory;

    public ConnectionCatalogService(DatabaseConfigRepository repository,
                                    DataSourceManager dataSourceManager,
                                    MetadataReaderFactory readerFactory) {
        this.repository = repository;
        this.dataSourceManager = dataSourceManager;
        this.readerFactory = readerFactory;
    }

    /**
     * The tables visible in the connection's effective schema.
     *
     * @throws IllegalArgumentException when the id is unknown or the connection speaks no JDBC
     * @throws IllegalStateException    when the database cannot be read; the message is a key
     */
    public Tables listTables(Long connectionId) {
        DatabaseConfig config = repository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("error.connection.missing"));
        if (!config.effectiveType().hasJdbcDriver()) {
            // Native MongoDB is a source-only URI; there is no JDBC connection to borrow.
            throw new IllegalArgumentException("error.mongodb.native.jdbcUnsupported");
        }
        MetadataReader reader = readerFactory.forType(config.getType(), config.resolvedFamily());
        try (Connection conn = dataSourceManager.getConnection(config)) {
            String configured = config.getSchemaName();
            String schema = configured != null && !configured.isBlank()
                    ? configured
                    : reader.resolveDefaultSchema(conn);
            return new Tables(schema, reader.listTableNames(conn, schema));
        } catch (SQLException e) {
            // Keep the driver's raw detail server-side; the browser only gets the key.
            log.debug("Cannot list tables for connection '{}': {}",
                    config.getName(), e.toString());
            throw new IllegalStateException("error.target.tables.read.failed", e);
        }
    }

    /** One connection's schema and table names. */
    public record Tables(String schema, List<String> tables) {
    }
}
