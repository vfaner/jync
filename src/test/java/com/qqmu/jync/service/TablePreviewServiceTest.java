package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.dto.TablePreview;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;

/**
 * Read-only preview of a table on either side of a project.
 *
 * <p>Runs against a real in-memory H2 connection: what is under test is the generated
 * SELECT and how its result set is shaped for the browser, neither of which a mocked
 * ResultSet would prove.
 */
class TablePreviewServiceTest {

    private Connection connection;
    private DatabaseConfig source;
    private DatabaseConfig target;
    private Project project;
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final DatabaseConfigRepository databaseConfigRepository =
            mock(DatabaseConfigRepository.class);
    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private TablePreviewService service;

    @BeforeEach
    void setUp() throws SQLException {
        connection = open();
        try (Statement st = connection.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }

        source = new DatabaseConfig();
        source.setId(10L);
        source.setName("Source DB");
        source.setType(DatabaseType.H2);
        source.setSchemaName("PUBLIC");

        target = new DatabaseConfig();
        target.setId(20L);
        target.setName("Target DB");
        target.setType(DatabaseType.H2);
        target.setSchemaName("PUBLIC");

        project = new Project();
        project.setId(1L);
        project.setName("Preview project");
        project.setSourceDbId(10L);
        project.setTargetDbId(20L);
        project.setSyncConfig("{}");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(databaseConfigRepository.findById(10L)).thenReturn(Optional.of(source));
        when(databaseConfigRepository.findById(20L)).thenReturn(Optional.of(target));
        // The service closes the connection it borrows (the pool recycles it in production),
        // so hand out a fresh one per call instead of letting the setup connection die.
        when(dataSourceManager.getConnection(source)).thenAnswer(inv -> open());
        when(dataSourceManager.getConnection(target)).thenAnswer(inv -> open());

        service = new TablePreviewService(projectRepository, databaseConfigRepository,
                dataSourceManager, new MetadataReaderFactory(List.of()),
                new SqlDialectFactory(List.of(), new GenericSqlDialect()), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        connection.close();
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:table_preview;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private void createOrders(int rows) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO orders (id, name) VALUES (?, ?)")) {
            for (int i = 1; i <= rows; i++) {
                ps.setLong(1, i);
                ps.setString(2, "order-" + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Test
    void capsThePreviewAtTheRowLimitAndFlagsTheTruncation() throws SQLException {
        createOrders(TablePreviewService.ROW_LIMIT + 50);

        TablePreview preview = service.preview(1L, "orders", TablePreviewService.Side.SOURCE);

        assertThat(preview.columns()).containsExactly("ID", "NAME");
        assertThat(preview.rows()).hasSize(TablePreviewService.ROW_LIMIT);
        assertThat(preview.rows().get(0)).containsExactly("1", "order-1");
        assertThat(preview.truncated()).isTrue();
    }

    @Test
    void usesTheMetadataSpellingOfTheTableSoAnUnquotedNameStillFindsIt() throws SQLException {
        createOrders(1);

        TablePreview preview = service.preview(1L, "orders", TablePreviewService.Side.SOURCE);

        assertThat(preview.table()).isEqualTo("ORDERS");
        assertThat(preview.rows()).hasSize(1);
    }

    @Test
    void refusesANameThatIsNotInTheMetadata() throws SQLException {
        createOrders(1);

        assertThatThrownBy(() -> service.preview(1L, "orders; DROP TABLE orders",
                TablePreviewService.Side.SOURCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.preview.table.unknown");
    }

    @Test
    void targetPreviewWithoutATargetConnectionIsRejectedWithAnI18nKey() {
        project.setTargetDbId(null);

        assertThatThrownBy(() -> service.preview(1L, "orders",
                TablePreviewService.Side.TARGET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.target.required");
    }

    @Test
    void formatsNullBinaryAndLongCellsForTransport() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE samples (id BIGINT, note CLOB, payload BLOB)");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO samples (id, note, payload) VALUES (?, ?, ?)")) {
            ps.setLong(1, 1);
            ps.setString(2, null);
            ps.setBytes(3, new byte[]{1, 2});
            ps.addBatch();
            ps.setLong(1, 2);
            ps.setString(2, "a".repeat(600));
            ps.setBytes(3, new byte[0]);
            ps.addBatch();
            ps.executeBatch();
        }

        TablePreview preview = service.preview(1L, "samples",
                TablePreviewService.Side.SOURCE);

        assertThat(preview.rows().get(0)).containsExactly("1", null, "<binary 2 bytes>");
        assertThat(preview.rows().get(1).get(1)).hasSize(501).endsWith("…");
        assertThat(preview.rows().get(1).get(2)).isEqualTo("<binary 0 bytes>");
    }

    @Test
    void previewsTheMappedTargetTableForTheTargetSide() throws SQLException {
        createOrders(1);
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE orders_copy (id BIGINT PRIMARY KEY, name VARCHAR(64))");
            st.execute("INSERT INTO orders_copy VALUES (900, 'on-the-target')");
        }
        project.setSyncConfig("{\"tableNameMapping\":{\"orders\":\"orders_copy\"}}");

        TablePreview preview = service.preview(1L, "orders",
                TablePreviewService.Side.TARGET);

        assertThat(preview.side()).isEqualTo("target");
        assertThat(preview.connection()).isEqualTo("Target DB");
        assertThat(preview.table()).isEqualTo("ORDERS_COPY");
        assertThat(preview.rows()).containsExactly(List.of("900", "on-the-target"));
    }
}
