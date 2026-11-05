package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.PrecheckReport;
import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.task.SyncContextFactory;

/**
 * A Redis target's precheck: the endpoint's read-only health, the projection contract, and the
 * source-side conditions a cache projection cannot survive. Nothing here writes.
 */
class RedisPrecheckServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final long TARGET_ID = 9L;
    private static final String SCHEMA = "shop";

    private RedisProbeFactory probeFactory;
    private DataSourceManager dataSourceManager;
    private SyncContextFactory contextFactory;
    private RedisProjectionPolicy policy;
    private CdcOperationsService cdcOperations;
    private CdcPositionStore cdcPositions;

    private Project project;
    private RedisTarget target;
    private DatabaseConfig sourceConfig;
    private Connection sourceConn;
    private MetadataReader reader;
    private SyncConfig config;
    private RedisProjectionConfig projection;
    private RedisProbe probe;

    @BeforeEach
    void setUp() throws Exception {
        probeFactory = mock(RedisProbeFactory.class);
        dataSourceManager = mock(DataSourceManager.class);
        contextFactory = mock(SyncContextFactory.class);
        policy = mock(RedisProjectionPolicy.class);
        cdcOperations = mock(CdcOperationsService.class);
        cdcPositions = mock(CdcPositionStore.class);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("cache-proj");
        project.setSourceDbId(2L);
        project.setTargetRedisId(TARGET_ID);

        target = new RedisTarget();
        target.setId(TARGET_ID);
        target.setName("cache-1");
        target.setHost("redis-1.local");
        target.setPort(6379);

        sourceConfig = new DatabaseConfig();
        sourceConfig.setName("mysql-src");
        sourceConfig.setType(DatabaseType.MYSQL);

        sourceConn = mock(Connection.class);
        reader = mock(MetadataReader.class);

        config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        projection = RedisProjectionConfig.defaultFor(PROJECT_ID);
        config.setRedisProjection(projection);

        sourceWith(orders(true));

        probe = mock(RedisProbe.class);
        when(probeFactory.open(target)).thenReturn(probe);
        when(probe.ping()).thenReturn("PONG");
        when(probe.maxmemoryPolicy()).thenReturn("allkeys-lru");
        when(cdcOperations.probeProject(PROJECT_ID)).thenReturn(CdcProbeResult.of(List.of()));
    }

    private RedisPrecheckService service() {
        return new RedisPrecheckService(probeFactory, dataSourceManager,
                contextFactory, policy, cdcOperations, cdcPositions);
    }

    private void sourceWith(TableMeta table) throws Exception {
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(table));
        SyncContext ctx = SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceConfig)
                .sourceType(DatabaseType.MYSQL)
                .sourceSchema(SCHEMA)
                .sourceReader(reader)
                .batchSize(500)
                .build();
        when(contextFactory.buildSourceOnly(project, config)).thenReturn(ctx);
        when(dataSourceManager.getConnection(sourceConfig)).thenReturn(sourceConn);
        when(reader.readAllForData(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(meta);
    }

    private List<PrecheckReport.Check> checks() {
        return service().precheck(project, target, config);
    }

    private static PrecheckReport.Check check(List<PrecheckReport.Check> checks, String code) {
        return checks.stream().filter(c -> code.equals(c.code())).findFirst().orElse(null);
    }

    private static TableMeta orders(boolean withPrimaryKey) {
        ColumnMeta id = column("ID", Types.INTEGER, "INT");
        ColumnMeta name = column("NAME", Types.VARCHAR, "VARCHAR");
        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        table.setColumns(List.of(id, name));
        table.setPrimaryKeys(withPrimaryKey ? List.of("ID") : List.of());
        return table;
    }

    private static ColumnMeta column(String name, int jdbcType, String typeName) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setTypeName(typeName);
        return column;
    }

    private static RedisTarget cluster() {
        RedisTarget target = new RedisTarget();
        target.setId(TARGET_ID);
        target.setName("cache-cluster");
        target.setHost("cluster.local");
        target.setPort(6379);
        target.setClusterMode(true);
        return target;
    }

    // —— endpoint -----------------------------------------------------------------------

    @Test
    void aFailedPingIsAnError() {
        when(probe.ping()).thenThrow(new RuntimeException("NOAUTH Authentication required"));

        PrecheckReport.Check ping = check(checks(), "redis.ping");

        assertThat(ping).isNotNull();
        assertThat(ping.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(ping.detail()).contains("NOAUTH");
    }

    @Test
    void anUnreachableTargetIsAnErrorAndDoesNotThrow() {
        when(probeFactory.open(target)).thenThrow(new RuntimeException("Connection refused"));

        List<PrecheckReport.Check> checks = checks();

        assertThat(check(checks, "redis.ping").severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        // Plaintext target: there is no TLS handshake to judge.
        assertThat(check(checks, "redis.tls").severity()).isEqualTo(PrecheckReport.Severity.SKIP);
    }

    @Test
    void aTlsHandshakeFailureIsAnErrorWhenTlsIsEnabled() {
        target.setTlsEnabled(true);
        target.setVerifyTls(true);
        when(probeFactory.open(target)).thenThrow(
                new RuntimeException("handshake failed", new SSLHandshakeException("bad cert")));

        List<PrecheckReport.Check> checks = checks();

        assertThat(check(checks, "redis.tls").severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(check(checks, "redis.tls").detail()).contains("handshake failed");
    }

    @Test
    void aHealthyTlsConnectionPassesTheTlsCheck() {
        target.setTlsEnabled(true);

        assertThat(check(checks(), "redis.tls").severity()).isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void aClusterDatabaseOtherThanZeroIsAnError() {
        RedisTarget cluster = cluster();
        cluster.setDatabaseIndex(3);
        when(probeFactory.open(cluster)).thenReturn(probe);
        when(probe.clusterInfo()).thenReturn("cluster_state:ok\ncluster_slots_assigned:16384");

        assertThat(check(service().precheck(project, cluster, config), "redis.database").severity())
                .isEqualTo(PrecheckReport.Severity.ERROR);
    }

    @Test
    void incompleteClusterSlotCoverageIsAnError() {
        RedisTarget cluster = cluster();
        when(probeFactory.open(cluster)).thenReturn(probe);
        when(probe.clusterInfo()).thenReturn("cluster_state:ok\ncluster_slots_assigned:16000");

        assertThat(check(service().precheck(project, cluster, config), "redis.cluster").severity())
                .isEqualTo(PrecheckReport.Severity.ERROR);
    }

    @Test
    void aHealthyClusterPasses() {
        RedisTarget cluster = cluster();
        when(probeFactory.open(cluster)).thenReturn(probe);
        when(probe.clusterInfo()).thenReturn("cluster_state:ok\ncluster_slots_assigned:16384");

        List<PrecheckReport.Check> checks = service().precheck(project, cluster, config);

        assertThat(check(checks, "redis.cluster").severity()).isEqualTo(PrecheckReport.Severity.OK);
        assertThat(check(checks, "redis.database").severity()).isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void aStandaloneTargetSkipsTheClusterCheck() {
        assertThat(check(checks(), "redis.cluster").severity())
                .isEqualTo(PrecheckReport.Severity.SKIP);
    }

    @Test
    void noevictionPolicyIsAWarning() {
        when(probe.maxmemoryPolicy()).thenReturn("noeviction");

        assertThat(check(checks(), "redis.memoryPolicy").severity())
                .isEqualTo(PrecheckReport.Severity.WARN);
    }

    @Test
    void anEvictionPolicyPasses() {
        assertThat(check(checks(), "redis.memoryPolicy").severity())
                .isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void anUnreadableMemoryPolicyIsAWarningNotAnError() {
        when(probe.maxmemoryPolicy()).thenThrow(new RuntimeException("NOPERM this user has no"));

        assertThat(check(checks(), "redis.memoryPolicy").severity())
                .isEqualTo(PrecheckReport.Severity.WARN);
    }

    @Test
    void aZeroTtlIsAWarningAboutOrphanKeys() {
        assertThat(check(checks(), "redis.ttl").severity())
                .isEqualTo(PrecheckReport.Severity.WARN);
    }

    @Test
    void aPositiveTtlPasses() {
        projection.setTtlSeconds(3600);

        assertThat(check(checks(), "redis.ttl").severity()).isEqualTo(PrecheckReport.Severity.OK);
    }

    // —— projection contract ------------------------------------------------------------

    @Test
    void aBlankPrefixIsAnError() {
        projection.setKeyPrefix("  ");

        PrecheckReport.Check prefix = check(checks(), "redis.prefix");

        assertThat(prefix.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(prefix.messageKey()).isEqualTo("precheck.redis.prefix.blank");
    }

    @Test
    void anUnterminatedPrefixIsAnError() {
        projection.setKeyPrefix("jync:1");

        assertThat(check(checks(), "redis.prefix").messageKey())
                .isEqualTo("precheck.redis.prefix.format");
    }

    @Test
    void aPrefixUsedByAnotherProjectIsAnError() {
        Project sibling = new Project();
        sibling.setId(7L);
        sibling.setName("other-cache");
        when(policy.conflictingProject(project, "jync:1:")).thenReturn(sibling);

        PrecheckReport.Check prefix = check(checks(), "redis.prefix");

        assertThat(prefix.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(prefix.detail()).contains("other-cache");
    }

    @Test
    void aValidPrefixPasses() {
        assertThat(check(checks(), "redis.prefix").severity()).isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void aMissingValueFormatIsAnError() {
        projection.setValueFormat(null);

        assertThat(check(checks(), "redis.valueFormat").severity())
                .isEqualTo(PrecheckReport.Severity.ERROR);
    }

    @Test
    void aValidValueFormatPasses() {
        assertThat(check(checks(), "redis.valueFormat").severity())
                .isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void anExcludedColumnThatExistsIsOk() {
        projection.setExcludedColumns(new LinkedHashSet<>(Set.of("NAME")));

        assertThat(check(checks(), "redis.excludedColumns").severity())
                .isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void anExcludedColumnThatDoesNotExistIsAWarning() {
        projection.setExcludedColumns(new LinkedHashSet<>(Set.of("NOPE")));

        PrecheckReport.Check excluded = check(checks(), "redis.excludedColumns");

        assertThat(excluded.severity()).isEqualTo(PrecheckReport.Severity.WARN);
        assertThat(excluded.detail()).contains("NOPE");
    }

    @Test
    void aLobColumnIsWarnedAbout() throws Exception {
        ColumnMeta payload = column("PAYLOAD", Types.BLOB, "BLOB");
        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        table.setColumns(List.of(column("ID", Types.INTEGER, "INT"), payload));
        table.setPrimaryKeys(List.of("ID"));
        sourceWith(table);

        PrecheckReport.Check lobs = check(checks(), "redis.lobColumns");

        assertThat(lobs.severity()).isEqualTo(PrecheckReport.Severity.WARN);
        assertThat(lobs.detail()).contains("PAYLOAD");
    }

    @Test
    void anExcludedLobColumnIsNotWarnedAbout() throws Exception {
        ColumnMeta payload = column("PAYLOAD", Types.BLOB, "BLOB");
        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        table.setColumns(List.of(column("ID", Types.INTEGER, "INT"), payload));
        table.setPrimaryKeys(List.of("ID"));
        sourceWith(table);
        projection.setExcludedColumns(new LinkedHashSet<>(Set.of("PAYLOAD")));

        assertThat(check(checks(), "redis.lobColumns").severity())
                .isEqualTo(PrecheckReport.Severity.OK);
    }

    // —— source -------------------------------------------------------------------------

    @Test
    void aKeylessTableIsAnError() throws Exception {
        sourceWith(orders(false));

        PrecheckReport.Check keys = check(checks(), "tables.primaryKey");

        assertThat(keys.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(keys.detail()).contains("ORDERS");
    }

    @Test
    void aKeyedTablePasses() {
        assertThat(check(checks(), "tables.primaryKey").severity())
                .isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void anUnreachableSourceIsAnErrorAndTheSourceChecksAreSkipped() throws Exception {
        when(dataSourceManager.getConnection(sourceConfig))
                .thenThrow(new java.sql.SQLException("Access denied"));

        List<PrecheckReport.Check> checks = checks();

        assertThat(check(checks, "source.metadata").severity())
                .isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(check(checks, "tables.primaryKey")).isNull();
        // The endpoint checks still ran.
        assertThat(check(checks, "redis.ping").severity()).isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void cdcProbePassesWhenEveryCapabilityIsPresent() {
        assertThat(check(checks(), "source.cdc").severity()).isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void aCdcProbeFailureIsAnError() {
        when(cdcOperations.probeProject(PROJECT_ID)).thenReturn(CdcProbeResult.of(List.of(
                CdcProbeResult.Check.of(CdcProbeResult.LOG_BIN, false, "log_bin is OFF"))));

        PrecheckReport.Check cdc = check(checks(), "source.cdc.LOG_BIN");

        assertThat(cdc.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(cdc.messageKey()).isEqualTo("cdc.check.LOG_BIN");
        assertThat(cdc.detail()).contains("log_bin is OFF");
    }

    @Test
    void binlogRowImageMinimalIsRejected() {
        when(cdcOperations.probeProject(PROJECT_ID)).thenReturn(CdcProbeResult.of(List.of(
                CdcProbeResult.Check.of(CdcProbeResult.BINLOG_ROW_IMAGE, false,
                        "binlog_row_image is MINIMAL"))));

        PrecheckReport.Check cdc = check(checks(), "source.cdc.BINLOG_ROW_IMAGE");

        assertThat(cdc.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(cdc.messageKey()).isEqualTo("cdc.check.BINLOG_ROW_IMAGE");
    }

    @Test
    void anUnsupportedCdcProbeIsSkippedNotFailed() {
        when(cdcOperations.probeProject(PROJECT_ID))
                .thenThrow(new IllegalArgumentException("cdc.probe.unsupported"));

        assertThat(check(checks(), "source.cdc").severity()).isEqualTo(PrecheckReport.Severity.SKIP);
    }

    // —— baseline and layout -------------------------------------------------------------

    @Test
    void aMissingBaselineIsAWarning() {
        when(cdcPositions.find(PROJECT_ID)).thenReturn(Optional.empty());

        assertThat(check(checks(), "baseline.done").severity())
                .isEqualTo(PrecheckReport.Severity.WARN);
        assertThat(check(checks(), "position.layout").severity())
                .isEqualTo(PrecheckReport.Severity.SKIP);
    }

    @Test
    void aCompletedBaselinePasses() {
        CdcPosition position = new CdcPosition();
        position.setBaselineDone(true);
        when(cdcPositions.find(PROJECT_ID)).thenReturn(Optional.of(position));
        when(cdcPositions.layoutSignatures(PROJECT_ID))
                .thenReturn(Map.of(SCHEMA + ".ORDERS", CdcTableBinding.layoutSignature(orders(true))));

        assertThat(check(checks(), "baseline.done").severity())
                .isEqualTo(PrecheckReport.Severity.OK);
    }

    @Test
    void aStoredContractThatStartWouldRefuseIsAnError() {
        // Start refuses a value format or prefix that no longer matches the contract the
        // existing keys were written with. A precheck that reports nothing here is worse than
        // no precheck: the user fixes every row it names and the start still fails.
        CdcPosition position = new CdcPosition();
        position.setBaselineDone(true);
        position.setProjectionSignature("HASH|jync:1:");
        when(cdcPositions.find(PROJECT_ID)).thenReturn(Optional.of(position));
        when(cdcPositions.layoutSignatures(PROJECT_ID)).thenReturn(Map.of());

        PrecheckReport.Check contract = check(checks(), "position.contract");

        assertThat(contract).isNotNull();
        assertThat(contract.severity()).isEqualTo(PrecheckReport.Severity.ERROR);
        assertThat(contract.messageKey()).isEqualTo("precheck.redis.contractMismatch");
        assertThat(contract.detail()).contains("HASH|jync:1:");
    }

    @Test
    void aMatchingStoredContractAddsNoRow() {
        CdcPosition position = new CdcPosition();
        position.setBaselineDone(true);
        position.setProjectionSignature("JSON|jync:1:");
        when(cdcPositions.find(PROJECT_ID)).thenReturn(Optional.of(position));
        when(cdcPositions.layoutSignatures(PROJECT_ID))
                .thenReturn(Map.of(SCHEMA + ".ORDERS", CdcTableBinding.layoutSignature(orders(true))));

        assertThat(check(checks(), "position.contract")).isNull();
    }

    @Test
    void anUnrecordedContractIsNotReportedAsAMismatch() {
        // Rows written before the column existed carry no signature; there is nothing to
        // compare, and inventing a mismatch would block a project whose cache is fine.
        CdcPosition position = new CdcPosition();
        position.setBaselineDone(true);
        when(cdcPositions.find(PROJECT_ID)).thenReturn(Optional.of(position));
        when(cdcPositions.layoutSignatures(PROJECT_ID)).thenReturn(Map.of());

        assertThat(check(checks(), "position.contract")).isNull();
    }

    @Test
    void aLayoutDriftIsAWarning() {
        CdcPosition position = new CdcPosition();
        position.setBaselineDone(true);
        when(cdcPositions.find(PROJECT_ID)).thenReturn(Optional.of(position));
        when(cdcPositions.layoutSignatures(PROJECT_ID))
                .thenReturn(Map.of(SCHEMA + ".ORDERS", "an-older-layout"));

        PrecheckReport.Check layout = check(checks(), "position.layout");

        assertThat(layout.severity()).isEqualTo(PrecheckReport.Severity.WARN);
        assertThat(layout.detail()).contains("ORDERS");
    }

    @Test
    void aMissingLayoutSignatureIsAWarning() {
        CdcPosition position = new CdcPosition();
        position.setBaselineDone(true);
        when(cdcPositions.find(PROJECT_ID)).thenReturn(Optional.of(position));
        when(cdcPositions.layoutSignatures(PROJECT_ID)).thenReturn(Map.of());

        assertThat(check(checks(), "position.layout").severity())
                .isEqualTo(PrecheckReport.Severity.WARN);
    }

    // —— read-only -----------------------------------------------------------------------

    @Test
    void precheckDoesNotWriteAnything() {
        checks();

        verify(cdcPositions, never()).advance(any(), any(), anyLong(), any(), any(),
                anyBoolean());
        // The only CDC entry point it touches is the read-only capability probe.
        verify(cdcOperations).probeProject(PROJECT_ID);
        verifyNoMoreInteractions(cdcOperations);
    }

    @Test
    void theReportIsPassedOnlyWithoutErrors() {
        assertThat(service().precheck(project, target, config).stream()
                .noneMatch(c -> c.severity() == PrecheckReport.Severity.ERROR)).isTrue();

        projection.setValueFormat(null);
        assertThat(service().precheck(project, target, config).stream()
                .anyMatch(c -> c.severity() == PrecheckReport.Severity.ERROR)).isTrue();
    }
}
