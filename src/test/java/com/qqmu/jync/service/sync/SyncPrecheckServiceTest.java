package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.PrecheckReport;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;
import com.qqmu.jync.service.task.SyncContextFactory;

/**
 * The precheck is a read-only rehearsal: it reports what a cycle would meet, and never
 * changes either side while doing so.
 */
class SyncPrecheckServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final String SCHEMA = "PUBLIC";

    private ProjectRepository projectRepository;
    private SyncContextFactory contextFactory;
    private DataSourceManager dataSourceManager;
    private SyncEngine syncEngine;
    private ChangeDetector changeDetector;
    private SyncProperties properties;
    private CursorStrategyResolver cursorResolver;
    private CdcOperationsService cdcOperations;
    private CdcPositionStore cdcPositions;

    private Project project;
    private DatabaseConfig sourceConfig;
    private DatabaseConfig targetConfig;
    private Connection sourceConn;
    private Connection targetConn;
    private String targetUrl;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        projectRepository = mock(ProjectRepository.class);
        contextFactory = mock(SyncContextFactory.class);
        dataSourceManager = mock(DataSourceManager.class);
        syncEngine = mock(SyncEngine.class);
        changeDetector = mock(ChangeDetector.class);
        properties = new SyncProperties();
        cursorResolver = new CursorStrategyResolver(properties);
        cdcOperations = mock(CdcOperationsService.class);
        cdcPositions = mock(CdcPositionStore.class);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("precheck-project");

        sourceConfig = new DatabaseConfig();
        sourceConfig.setName("source");
        sourceConfig.setType(DatabaseType.H2);
        targetConfig = new DatabaseConfig();
        targetConfig.setName("target");
        targetConfig.setType(DatabaseType.H2);

        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        sourceConn = DriverManager.getConnection(
                "jdbc:h2:mem:precheck_src_" + suffix + ";DB_CLOSE_DELAY=-1", "sa", "");
        targetUrl = "jdbc:h2:mem:precheck_tgt_" + suffix + ";DB_CLOSE_DELAY=-1";
        targetConn = DriverManager.getConnection(targetUrl, "sa", "");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(dataSourceManager.getConnection(sourceConfig)).thenReturn(sourceConn);
        when(dataSourceManager.getConnection(targetConfig)).thenReturn(targetConn);
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    private SyncPrecheckService service() {
        return service(changeDetector);
    }

    private SyncPrecheckService service(ChangeDetector detector) {
        return new SyncPrecheckService(projectRepository, contextFactory, dataSourceManager,
                syncEngine, detector, cursorResolver, cdcOperations, cdcPositions, properties);
    }

    private SyncContext context(SyncConfig config) {
        ctx = SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceConfig)
                .targetConfig(targetConfig)
                .sourceType(DatabaseType.H2)
                .targetType(DatabaseType.H2)
                .sourceSchema(SCHEMA)
                .targetSchema(SCHEMA)
                .sourceReader(mock(com.qqmu.jync.service.metadata.MetadataReader.class))
                .targetReader(mock(com.qqmu.jync.service.metadata.MetadataReader.class))
                .sourceDialect(mock(com.qqmu.jync.service.converter.SqlDialect.class))
                .targetDialect(mock(com.qqmu.jync.service.converter.SqlDialect.class))
                .batchSize(500)
                .build();
        when(contextFactory.build(any(), any(), any())).thenReturn(ctx);
        return ctx;
    }

    private static SyncConfig config(boolean syncStructure) {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(syncStructure);
        config.setSyncData(true);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncIndexes(false);
        return config;
    }

    private static ColumnMeta column(String name, int jdbcType, String typeName) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setTypeName(typeName);
        column.setSize(64);
        return column;
    }

    private static TableMeta orders(ColumnMeta... columns) {
        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        table.setColumns(List.of(columns));
        table.setPrimaryKeys(List.of("ID"));
        table.setApproximateRowCount(10L);
        return table;
    }

    private static DatabaseMeta sourceWith(TableMeta table) {
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(table));
        return meta;
    }

    private void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private void stubNoStructureEvents(DatabaseMeta meta, SyncConfig config) {
        when(changeDetector.detect(eq(PROJECT_ID), eq(meta), eq(config), any()))
                .thenReturn(List.of());
    }

    private static PrecheckReport.Check check(PrecheckReport report, String code) {
        return report.checks().stream()
                .filter(c -> code.equals(c.code()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void reportsMissingTargetTableAsErrorWhenStructureSyncOff() throws Exception {
        SyncConfig config = config(false);
        context(config);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        DatabaseMeta meta = sourceWith(table);
        when(syncEngine.readSourceMetadata(sourceConn, ctx)).thenReturn(meta);
        stubNoStructureEvents(meta, config);

        PrecheckReport report = service().precheck(PROJECT_ID);

        assertThat(check(report, "target.table.missing")).isNotNull();
        assertThat(check(report, "target.table.missing").severity())
                .isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void warnsWhenStructureSyncWillCreateIt() throws Exception {
        SyncConfig config = config(true);
        context(config);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        DatabaseMeta meta = sourceWith(table);
        when(syncEngine.readSourceMetadata(sourceConn, ctx)).thenReturn(meta);
        stubNoStructureEvents(meta, config);

        PrecheckReport report = service().precheck(PROJECT_ID);

        assertThat(check(report, "target.table.missing")).isNotNull();
        assertThat(check(report, "target.table.missing").severity())
                .isEqualTo(PrecheckReport.Severity.WARN);
    }

    @Test
    void reportsMissingTargetColumns() throws Exception {
        execute(targetConn, "CREATE TABLE ORDERS (ID INT PRIMARY KEY)");
        SyncConfig config = config(false);
        context(config);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("NAME", Types.VARCHAR, "VARCHAR"));
        DatabaseMeta meta = sourceWith(table);
        when(syncEngine.readSourceMetadata(sourceConn, ctx)).thenReturn(meta);
        stubNoStructureEvents(meta, config);

        PrecheckReport report = service().precheck(PROJECT_ID);

        PrecheckReport.Check missing = check(report, "target.table.columns");
        assertThat(missing).isNotNull();
        assertThat(missing.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(missing.detail()).contains("NAME");
    }

    @Test
    void reportsIncompatibleTargetType() throws Exception {
        execute(targetConn, "CREATE TABLE ORDERS (ID VARCHAR(10) PRIMARY KEY,"
                + " UPDATED_AT TIMESTAMP)");
        SyncConfig config = config(false);
        context(config);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        DatabaseMeta meta = sourceWith(table);
        when(syncEngine.readSourceMetadata(sourceConn, ctx)).thenReturn(meta);
        stubNoStructureEvents(meta, config);

        PrecheckReport report = service().precheck(PROJECT_ID);

        PrecheckReport.Check incompatible = check(report, "target.table.incompatible");
        assertThat(incompatible).isNotNull();
        assertThat(incompatible.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(incompatible.detail()).contains("ID");
    }

    @Test
    void reportsPendingStructureEventsWithoutApplyingThem() throws Exception {
        SyncConfig config = config(true);
        context(config);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        DatabaseMeta meta = sourceWith(table);
        when(syncEngine.readSourceMetadata(sourceConn, ctx)).thenReturn(meta);
        ChangeEvent create = ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "ORDERS",
                "New table", table);
        ChangeEvent alter = ChangeEvent.of(ObjectType.COLUMN, ChangeType.ALTER, "ORDERS",
                "New column", null);
        when(changeDetector.detect(eq(PROJECT_ID), eq(meta), eq(config), any()))
                .thenReturn(List.of(create, alter));

        PrecheckReport report = service().precheck(PROJECT_ID);

        List<PrecheckReport.Check> pending = report.checks().stream()
                .filter(c -> "structure.pending".equals(c.code())).toList();
        assertThat(pending).hasSize(2);
        assertThat(pending).allMatch(c -> c.severity() == PrecheckReport.Severity.WARN);
        // The report describes the work; it must not create the table.
        assertThat(tableCount("ORDERS")).isZero();
    }

    @Test
    void respectsTargetTableNameMapping() throws Exception {
        execute(targetConn, "CREATE TABLE ORDERS_V2 (ID INT PRIMARY KEY,"
                + " UPDATED_AT TIMESTAMP)");
        SyncConfig config = config(false);
        config.setTableNameMapping(Map.of("ORDERS", "ORDERS_V2"));
        context(config);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        DatabaseMeta meta = sourceWith(table);
        when(syncEngine.readSourceMetadata(sourceConn, ctx)).thenReturn(meta);
        stubNoStructureEvents(meta, config);

        PrecheckReport report = service().precheck(PROJECT_ID);

        assertThat(check(report, "target.table.missing")).isNull();
        PrecheckReport.Check exists = check(report, "target.table");
        assertThat(exists).isNotNull();
        assertThat(exists.objectName()).isEqualTo("ORDERS");
        assertThat(report.passed()).isTrue();
    }

    @Test
    void unreachableSourceReturnsReportWithErrorNotException() throws Exception {
        SyncConfig config = config(false);
        context(config);
        when(dataSourceManager.getConnection(sourceConfig))
                .thenThrow(new SQLException("connection refused"));

        assertThatCode(() -> {
            PrecheckReport report = service().precheck(PROJECT_ID);
            assertThat(check(report, "connection.source")).isNotNull();
            assertThat(check(report, "connection.source").severity())
                    .isEqualTo(PrecheckReport.Severity.ERROR);
            assertThat(report.passed()).isFalse();
        }).doesNotThrowAnyException();
    }

    @Test
    void doesNotWriteAnything() throws Exception {
        execute(targetConn, "CREATE TABLE ORDERS (ID INT PRIMARY KEY,"
                + " UPDATED_AT TIMESTAMP)");
        SyncConfig config = config(true);
        context(config);
        MetadataSnapshotService snapshots = mock(MetadataSnapshotService.class);
        when(snapshots.loadAll(PROJECT_ID, ObjectType.TABLE, TableMeta.class))
                .thenReturn(Map.of());
        ChangeDetector realDetector = new ChangeDetector(snapshots);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        DatabaseMeta meta = sourceWith(table);
        when(syncEngine.readSourceMetadata(sourceConn, ctx)).thenReturn(meta);

        service(realDetector).precheck(PROJECT_ID);

        verify(snapshots, never()).save(any(), any(), any(), any());
        verify(projectRepository, never()).save(any());
        // The pending CREATE was reported, not applied.
        assertThat(tableCount("ORDERS")).isOne();
    }

    @Test
    void passedIsFalseOnlyForErrors() throws Exception {
        execute(targetConn, "CREATE TABLE ORDERS (ID INT PRIMARY KEY,"
                + " UPDATED_AT TIMESTAMP)");
        SyncConfig config = config(false);
        context(config);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        DatabaseMeta meta = sourceWith(table);
        when(syncEngine.readSourceMetadata(sourceConn, ctx)).thenReturn(meta);
        stubNoStructureEvents(meta, config);

        PrecheckReport passed = service().precheck(PROJECT_ID);
        assertThat(passed.count(PrecheckReport.Severity.ERROR)).isZero();
        assertThat(passed.passed()).isTrue();

        SyncConfig failing = config(false);
        context(failing);
        TableMeta withExtraColumn = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"),
                column("EXTRA", Types.VARCHAR, "VARCHAR"));
        DatabaseMeta failingMeta = sourceWith(withExtraColumn);
        when(syncEngine.readSourceMetadata(sourceConn, ctx)).thenReturn(failingMeta);
        stubNoStructureEvents(failingMeta, failing);

        PrecheckReport failed = service().precheck(PROJECT_ID);
        assertThat(failed.count(PrecheckReport.Severity.ERROR)).isPositive();
        assertThat(failed.passed()).isFalse();
    }

    // —— CDC checks (Task 3) ——

    private static SyncConfig cdcConfig(CaptureStrategy strategy) {
        SyncConfig config = config(false);
        config.setCaptureStrategy(strategy);
        return config;
    }

    private static CdcPosition cdcPosition(boolean baselineDone, String signatures) {
        CdcPosition position = new CdcPosition();
        position.setProjectId(PROJECT_ID);
        position.setBaselineDone(baselineDone);
        position.setSchemaSignatures(signatures);
        return position;
    }

    /** Mirrors {@code CdcPositionStore}'s on-disk encoding. */
    private static String encodedLayouts(Map<String, String> signatures) {
        StringBuilder encoded = new StringBuilder();
        signatures.forEach((key, signature) ->
                encoded.append(key.length()).append(':').append(key).append(signature));
        return encoded.toString();
    }

    private void givenPosition(CdcPosition position) {
        when(cdcPositions.find(PROJECT_ID)).thenReturn(Optional.of(position));
        when(cdcPositions.layoutSignatures(PROJECT_ID))
                .thenReturn(decodeLayouts(position.getSchemaSignatures()));
    }

    /** Mirrors the store's decoder; the service reads layouts through it. */
    private static Map<String, String> decodeLayouts(String encoded) {
        Map<String, String> signatures = new java.util.LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return signatures;
        }
        int i = 0;
        while (i < encoded.length()) {
            int colon = encoded.indexOf(':', i);
            if (colon < 0) {
                break;
            }
            int keyLength = Integer.parseInt(encoded.substring(i, colon));
            int keyStart = colon + 1;
            signatures.put(encoded.substring(keyStart, keyStart + keyLength),
                    encoded.substring(keyStart + keyLength, keyStart + keyLength + 64));
            i = keyStart + keyLength + 64;
        }
        return signatures;
    }

    private PrecheckReport cdcProject(SyncConfig config, TableMeta table,
                                      CdcProbeResult probe) throws Exception {
        context(config);
        DatabaseMeta meta = sourceWith(table);
        when(syncEngine.readSourceMetadata(sourceConn, ctx)).thenReturn(meta);
        stubNoStructureEvents(meta, config);
        when(cdcOperations.probeProject(PROJECT_ID)).thenReturn(probe);
        return service().precheck(PROJECT_ID);
    }

    @Test
    void reportsCdcProbeFailuresAsErrors() throws Exception {
        SyncConfig config = cdcConfig(CaptureStrategy.BINLOG_CDC);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        givenPosition(cdcPosition(true, encodedLayouts(Map.of("PUBLIC.ORDERS",
                CdcTableBinding.layoutSignature(table)))));

        PrecheckReport report = cdcProject(config, table, CdcProbeResult.of(
                List.of(CdcProbeResult.Check.of("BINLOG_FORMAT", false,
                        "ROW is required, server reports STATEMENT"))));

        PrecheckReport.Check probe = check(report, "cdc.probe.BINLOG_FORMAT");
        assertThat(probe).isNotNull();
        assertThat(probe.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(probe.messageKey()).isEqualTo("cdc.check.BINLOG_FORMAT");
        assertThat(probe.detail()).contains("ROW is required");
    }

    @Test
    void reportsCdcLayoutDriftForBinlog() throws Exception {
        SyncConfig config = cdcConfig(CaptureStrategy.BINLOG_CDC);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        String stale = "0".repeat(64);
        givenPosition(cdcPosition(true, encodedLayouts(Map.of("PUBLIC.ORDERS", stale))));

        PrecheckReport report = cdcProject(config, table, CdcProbeResult.of(List.of()));

        PrecheckReport.Check drift = check(report, "cdc.layout.drift");
        assertThat(drift).isNotNull();
        assertThat(drift.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(drift.detail()).contains(com.qqmu.jync.service.cdc.CdcSchemaDrift.MARKER)
                .contains("re-baseline");
    }

    @Test
    void acceptsMatchingCdcLayout() throws Exception {
        SyncConfig config = cdcConfig(CaptureStrategy.BINLOG_CDC);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        givenPosition(cdcPosition(true, encodedLayouts(Map.of("public.orders",
                CdcTableBinding.layoutSignature(table)))));

        PrecheckReport report = cdcProject(config, table, CdcProbeResult.of(List.of()));

        assertThat(check(report, "cdc.layout.drift")).isNull();
        assertThat(check(report, "cdc.probe")).isNotNull();
        assertThat(check(report, "cdc.probe").severity())
                .isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void warnsWhenCdcPositionHasNoLayoutSignatures() throws Exception {
        SyncConfig config = cdcConfig(CaptureStrategy.BINLOG_CDC);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        givenPosition(cdcPosition(true, null));

        PrecheckReport report = cdcProject(config, table, CdcProbeResult.of(List.of()));

        PrecheckReport.Check missing = check(report, "cdc.layout.missing");
        assertThat(missing).isNotNull();
        assertThat(missing.severity()).isEqualTo(PrecheckReport.Severity.WARN);
    }

    @Test
    void warnsWhenBaselineIsNotDone() throws Exception {
        SyncConfig config = cdcConfig(CaptureStrategy.BINLOG_CDC);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        givenPosition(cdcPosition(false, encodedLayouts(Map.of("PUBLIC.ORDERS", "0".repeat(64)))));

        PrecheckReport report = cdcProject(config, table, CdcProbeResult.of(List.of()));

        PrecheckReport.Check pending = check(report, "cdc.baseline.pending");
        assertThat(pending).isNotNull();
        assertThat(pending.severity()).isEqualTo(PrecheckReport.Severity.WARN);
        // Without a baseline the stored layouts describe nothing reliable yet.
        assertThat(check(report, "cdc.layout.drift")).isNull();
    }

    @Test
    void warnsWhenTheProjectHasNoCdcPositionYet() throws Exception {
        SyncConfig config = cdcConfig(CaptureStrategy.BINLOG_CDC);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));
        when(cdcPositions.find(PROJECT_ID)).thenReturn(Optional.empty());

        PrecheckReport report = cdcProject(config, table, CdcProbeResult.of(List.of()));

        assertThat(check(report, "cdc.baseline.pending")).isNotNull();
    }

    @Test
    void doesNotRunCdcProbeForPollingProject() throws Exception {
        SyncConfig config = config(false);
        TableMeta table = orders(column("ID", Types.INTEGER, "INTEGER"),
                column("UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP"));

        PrecheckReport report = cdcProject(config, table, CdcProbeResult.of(List.of()));

        verify(cdcOperations, never()).probeProject(any());
        verify(cdcPositions, never()).find(any());
        assertThat(check(report, "cdc.probe")).isNull();
    }

    private long tableCount(String table) throws SQLException {
        // The service closes the connections it borrowed, so ask the same in-memory database
        // through a fresh one. DB_CLOSE_DELAY=-1 keeps it alive between the two.
        try (Connection fresh = DriverManager.getConnection(targetUrl, "sa", "");
             Statement st = fresh.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                     + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = '" + table + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
