package com.qqmu.jync.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.TablePreview;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.util.JdbcUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only preview of a synced table on the source or the target side.
 *
 * <p>This is a look, not a database client: the SQL is always a fixed
 * {@code SELECT * FROM <schema>.<table>} over a name that came from the project's own
 * metadata, the row count is capped, and nothing here can write. It exists so an operator
 * can confirm what actually landed on the target without opening a separate JDBC tool.
 */
@Service
@Slf4j
public class TablePreviewService {

    /** Rows shown per preview. The modal states the cap; one extra row is read to detect it. */
    public static final int ROW_LIMIT = 100;

    /** Keeps a runaway preview from occupying a request thread for the sync-wide timeout. */
    private static final int PREVIEW_TIMEOUT_SECONDS = 30;

    /** Long text and binary cells are cut for transport; the modal is a glance, not an export. */
    private static final int CELL_MAX_LENGTH = 500;

    public enum Side { SOURCE, TARGET }

    private final ProjectRepository projectRepository;
    private final DatabaseConfigRepository databaseConfigRepository;
    private final DataSourceManager dataSourceManager;
    private final MetadataReaderFactory readerFactory;
    private final SqlDialectFactory dialectFactory;
    private final ObjectMapper objectMapper;

    public TablePreviewService(ProjectRepository projectRepository,
                               DatabaseConfigRepository databaseConfigRepository,
                               DataSourceManager dataSourceManager,
                               MetadataReaderFactory readerFactory,
                               SqlDialectFactory dialectFactory,
                               ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.databaseConfigRepository = databaseConfigRepository;
        this.dataSourceManager = dataSourceManager;
        this.readerFactory = readerFactory;
        this.dialectFactory = dialectFactory;
        this.objectMapper = objectMapper;
    }

    public TablePreview preview(Long projectId, String table, Side side) {
        long started = System.currentTimeMillis();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        Long databaseId = side == Side.SOURCE ? project.getSourceDbId() : project.getTargetDbId();
        if (databaseId == null) {
            throw new IllegalStateException(side == Side.SOURCE
                    ? "error.project.source.required" : "error.project.target.required");
        }
        DatabaseConfig config = databaseConfigRepository.findById(databaseId)
                .orElseThrow(() -> new IllegalStateException("error.connection.missing"));

        // The declared family, not the bare type: a CUSTOM connection carries GENERIC and
        // would otherwise preview through the generic reader and the generic dialect.
        MetadataReader reader = readerFactory.forType(config.getType(), config.resolvedFamily());
        SqlDialect dialect = dialectFactory.forType(config.getType(), null, config.resolvedFamily());
        try (Connection conn = dataSourceManager.getConnection(config)) {
            String schema = resolveSchema(config, reader, conn);
            String requested = side == Side.TARGET
                    ? parseConfig(project).targetTableName(table) : table;
            String actualTable = resolveTableName(conn, reader, schema, requested);
            return read(conn, dialect, schema, actualTable, config.getName(), side, started);
        } catch (SQLException e) {
            // Keep driver detail server-side; the browser only resolves the key.
            log.debug("Cannot preview '{}' on the {} side of project '{}': {}",
                    table, side, project.getName(), e.toString());
            throw new IllegalStateException("error.preview.read.failed", e);
        }
    }

    private TablePreview read(Connection conn, SqlDialect dialect, String schema, String table,
                              String connectionName, Side side, long started)
            throws SQLException {
        String sql = "SELECT * FROM " + dialect.qualify(schema, table);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // LIMIT is not portable across every supported product; setMaxRows is.
            ps.setMaxRows(ROW_LIMIT + 1);
            JdbcUtil.applyQueryTimeout(ps, PREVIEW_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> columns = new ArrayList<>(meta.getColumnCount());
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                List<List<String>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == ROW_LIMIT) {
                        truncated = true;
                        break;
                    }
                    rows.add(readRow(rs, meta.getColumnCount()));
                }
                // Measured here, after the rows are read: the modal shows this as how long
                // the preview took, and computing it before the SELECT reported ~0 ms.
                return new TablePreview(side.name().toLowerCase(java.util.Locale.ROOT),
                        connectionName, schema, table, columns, rows, truncated,
                        System.currentTimeMillis() - started);
            }
        }
    }

    private List<String> readRow(ResultSet rs, int columnCount) throws SQLException {
        List<String> row = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            row.add(format(rs.getObject(i)));
        }
        return row;
    }

    private String format(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return binaryPlaceholder(bytes.length);
        }
        // H2, Oracle and others hand BLOB/CLOB back as locator objects, whose toString is
        // an internal descriptor ("blob0: X'…'") — useful to nobody and not the data.
        if (value instanceof java.sql.Blob blob) {
            return binaryPlaceholder((int) Math.min(blob.length(), Integer.MAX_VALUE));
        }
        if (value instanceof java.sql.Clob clob) {
            long length = clob.length();
            return truncate(clob.getSubString(1, (int) Math.min(length, CELL_MAX_LENGTH + 1L)));
        }
        return truncate(String.valueOf(value));
    }

    private String truncate(String text) {
        return text.length() > CELL_MAX_LENGTH
                ? text.substring(0, CELL_MAX_LENGTH) + "…" : text;
    }

    private String binaryPlaceholder(int length) {
        return "<binary " + length + " bytes>";
    }

    /** Mirrors SyncContextFactory: a corrupt config means defaults, never a dead page. */
    private SyncConfig parseConfig(Project project) {
        String json = project.getSyncConfig();
        if (json == null || json.isBlank()) {
            return new SyncConfig();
        }
        try {
            return objectMapper.readValue(json, SyncConfig.class);
        } catch (Exception e) {
            log.error("Could not parse sync config for project '{}'; using defaults: {}",
                    project.getName(), e.getMessage());
            return new SyncConfig();
        }
    }

    /**
     * Maps the requested name onto the spelling the database actually uses.
     *
     * <p>The request carries the name from the progress row, which may differ in case from
     * the dictionary (H2 upper-cases unquoted names, Oracle folds them, PostgreSQL does not).
     * Quoting a non-matching spelling would look for a case-sensitive twin that does not
     * exist — and, more importantly, only a name from this list is ever put into the SELECT.
     */
    private String resolveTableName(Connection conn, MetadataReader reader, String schema,
                                    String requested) throws SQLException {
        List<String> names = new ArrayList<>(reader.listTableNames(conn, schema));
        names.addAll(reader.listViewNames(conn, schema));
        for (String name : names) {
            if (name.equalsIgnoreCase(requested)) {
                return name;
            }
        }
        throw new IllegalStateException("error.preview.table.unknown");
    }

    /** Uses the configured schema, or asks the database what the connection's default is. */
    private String resolveSchema(DatabaseConfig config, MetadataReader reader, Connection conn)
            throws SQLException {
        if (config.getSchemaName() != null && !config.getSchemaName().isBlank()) {
            String schema = config.getSchemaName().trim();
            // Oracle-family dictionaries fold unquoted identifiers to upper case; quoting the
            // user's lower-case spelling would target a case-sensitive twin that does not exist.
            return config.resolvedFamily() == DatabaseType.DialectFamily.ORACLE
                    ? schema.toUpperCase(java.util.Locale.ROOT) : schema;
        }
        return reader.resolveDefaultSchema(conn);
    }
}
