package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.LockLostException;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The first full projection of a Redis target: anchor the stream first, page every selected
 * table by primary key, write each page as one pipeline, and only then mark the baseline done.
 */
class RedisBaselineServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long TARGET_ID = 9L;

    private Connection conn;
    private DataSourceManager dataSourceManager;
    private RedisTargetService targetService;
    private RedisProjectionWriter writer;
    private CdcPositionStore positionStore;
    private SyncStateWriter stateWriter;
    private SyncTaskStore taskStore;
    private MetadataReader reader;
    private DatabaseConfig sourceConfig;
    private RedisTarget target;
    private RedisProjectionConfig projection;
    private DatabaseMeta sourceMeta;

    private RedisBaselineService service;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:redis_baseline;DB_CLOSE_DELAY=-1");
        try (Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE TABLE ORDERS (ID INT PRIMARY KEY, NAME VARCHAR(50))");
            st.execute("INSERT INTO ORDERS VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,'e')");
            st.execute("CREATE TABLE EVENTS (LOG_ID INT PRIMARY KEY, PAYLOAD VARCHAR(50))");
            st.execute("INSERT INTO EVENTS VALUES (1,'x'),(2,'y')");
            st.execute("CREATE TABLE NOPK (TXT VARCHAR(50))");
            st.execute("INSERT INTO NOPK VALUES ('z')");
        }

        dataSourceManager = mock(DataSourceManager.class);
        targetService = mock(RedisTargetService.class);
        writer = mock(RedisProjectionWriter.class);
        positionStore = mock(CdcPositionStore.class);
        stateWriter = mock(SyncStateWriter.class);
        taskStore = mock(SyncTaskStore.class);
        reader = mock(MetadataReader.class);

        sourceConfig = new DatabaseConfig();
        sourceConfig.setId(5L);
        sourceConfig.setName("src");
        sourceConfig.setType(DatabaseType.H2);
        sourceConfig.setSchemaName("PUBLIC");
        when(dataSourceManager.getConnection(sourceConfig)).thenReturn(conn);

        target = new RedisTarget();
        target.setId(TARGET_ID);
        target.setHost("127.0.0.1");
        target.setPort(6379);
        when(targetService.find(TARGET_ID)).thenReturn(Optional.of(target));

        sourceMeta = new DatabaseMeta();
        sourceMeta.setTables(List.of(
                table("ORDERS", List.of("ID", "NAME"), List.of("ID")),
                table("EVENTS", List.of("LOG_ID", "PAYLOAD"), List.of("LOG_ID")),
                table("NOPK", List.of("TXT"), List.of())));
        when(reader.readAllForData(any(), eq("PUBLIC"), any(), any(), any(),
                anyBoolean(), anyBoolean())).thenReturn(sourceMeta);

        projection = RedisProjectionConfig.defaultFor(PROJECT_ID);
        service = new RedisBaselineService(dataSourceManager, targetService, writer,
                positionStore, stateWriter, taskStore);
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    void redisBaselineWritesEverySelectedTable() {
        service.run(context(Set.of("ORDERS", "EVENTS"), 100), () -> true, ctx -> null);

        List<RedisMutation> sent = mutations();
        assertThat(sent).hasSize(7);
        assertThat(sent).extracting(RedisMutation::key).contains(
                "jync:1:ORDERS:1", "jync:1:ORDERS:5", "jync:1:EVENTS:2");
        verify(positionStore).markBaselineDone(PROJECT_ID, "JSON|jync:1:");
        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(ObjectType.PROJECT), any(),
                eq(ChangeType.INFO), contains("Redis baseline"), eq(true), anyLong(), eq(7));
    }

    @Test
    void keylessTableIsSkippedWithWarning() {
        service.run(context(Set.of("ORDERS", "NOPK"), 100), () -> true, ctx -> null);

        assertThat(mutations()).hasSize(5)
                .extracting(RedisMutation::key)
                .noneMatch(key -> key.contains("NOPK"));
        verify(positionStore).markBaselineDone(PROJECT_ID, "JSON|jync:1:");
    }

    @Test
    void resumeKeyPaginationUsesPrimaryKeyOrder() {
        service.run(context(Set.of("ORDERS"), 2), () -> true, ctx -> null);

        ArgumentCaptor<List<RedisMutation>> captor = captor();
        verify(writer, times(3)).apply(eq(target), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(chunk -> chunk.stream().map(RedisMutation::key).toList())
                .containsExactly(
                        List.of("jync:1:ORDERS:1", "jync:1:ORDERS:2"),
                        List.of("jync:1:ORDERS:3", "jync:1:ORDERS:4"),
                        List.of("jync:1:ORDERS:5"));
    }

    @Test
    void tableNameMappingAppliesToBaselineKeys() {
        SyncConfig config = config(Set.of("ORDERS"), 100);
        config.setTableNameMapping(new LinkedHashMap<>(Map.of("ORDERS", "orders_v2")));

        service.run(context(config), () -> true, ctx -> null);

        assertThat(mutations()).extracting(RedisMutation::key)
                .contains("jync:1:orders_v2:1");
    }

    @Test
    void columnRulesApplyToTheProjectedValues() {
        SyncConfig config = config(Set.of("ORDERS"), 100);
        ColumnRule rule = new ColumnRule();
        rule.setTable("ORDERS");
        rule.setColumn("NAME");
        rule.setType(ColumnRuleType.UPPER);
        config.setColumnRules(new ArrayList<>(List.of(rule)));

        service.run(context(config), () -> true, ctx -> null);

        assertThat(mutations()).extracting(RedisMutation::jsonValue)
                .contains("{\"ID\":1,\"NAME\":\"A\"}");
    }

    @Test
    void thePageCursorUsesTheRawKeyBeforeColumnRulesRewriteIt() throws SQLException {
        // A rule on a primary-key column rewrites the value the cache key is built from, but
        // the next page's keyset predicate compares against the SOURCE values. Advancing the
        // cursor with the cleansed value made "CODE > 'b'" skip the raw keys 'C'..'E' — a
        // LOWER rule on the key silently dropped three of five rows. The JDBC full load
        // captures its boundary before the cleanser for exactly this reason.
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE CODES (CODE VARCHAR(10) PRIMARY KEY, VAL VARCHAR(20))");
            st.execute("INSERT INTO CODES VALUES ('A','1'),('B','2'),('C','3'),('D','4'),"
                    + "('E','5')");
        }
        DatabaseMeta meta = new DatabaseMeta();
        List<TableMeta> tables = new ArrayList<>(sourceMeta.getTables());
        tables.add(table("CODES", List.of("CODE", "VAL"), List.of("CODE")));
        meta.setTables(tables);
        when(reader.readAllForData(any(), eq("PUBLIC"), any(), any(), any(),
                anyBoolean(), anyBoolean())).thenReturn(meta);

        SyncConfig config = config(Set.of("CODES"), 2);
        ColumnRule rule = new ColumnRule();
        rule.setTable("CODES");
        rule.setColumn("CODE");
        rule.setType(ColumnRuleType.LOWER);
        config.setColumnRules(new ArrayList<>(List.of(rule)));

        service.run(context(config), () -> true, ctx -> null);

        assertThat(mutations()).extracting(RedisMutation::key)
                .containsExactlyInAnyOrder("jync:1:CODES:a", "jync:1:CODES:b",
                        "jync:1:CODES:c", "jync:1:CODES:d", "jync:1:CODES:e");
    }

    @Test
    void failureDoesNotMarkBaselineDone() {
        doThrow(new RedisProjectionException("boom")).when(writer).apply(any(), anyList());

        assertThatThrownBy(() -> service.run(context(Set.of("ORDERS"), 100), () -> true,
                ctx -> null))
                .isInstanceOf(RedisProjectionException.class);

        verify(positionStore, never()).markBaselineDone(anyLong());
        verify(stateWriter, never()).recordLog(anyLong(), any(), any(), any(), any(),
                anyBoolean(), anyLong(), any());
    }

    @Test
    void lostLeaseStopsBetweenPages() {
        assertThatThrownBy(() -> service.run(context(Set.of("ORDERS"), 2), () -> false,
                ctx -> null))
                .isInstanceOf(LockLostException.class);

        verify(writer, times(1)).apply(eq(target), anyList());
        verify(positionStore, never()).markBaselineDone(anyLong());
    }

    @Test
    void baselineRecordsTheStrategyAnchorBeforeAnyWrite() {
        AtomicBoolean anchored = new AtomicBoolean();
        doAnswer(inv -> {
            assertThat(anchored).as("anchor must be recorded before rows are projected").isTrue();
            return null;
        }).when(writer).apply(any(), anyList());

        service.run(context(Set.of("ORDERS"), 100), () -> true, ctx -> {
            anchored.set(true);
            return null;
        });

        assertThat(anchored).isTrue();
        verify(positionStore).markBaselineDone(PROJECT_ID, "JSON|jync:1:");
        verify(taskStore).markCdcDataApplied(PROJECT_ID);
    }

    // —— helpers ——

    private SyncContext context(Set<String> tables, int batchSize) {
        return context(config(tables, batchSize));
    }

    private SyncContext context(SyncConfig config) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("orders-cache");
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(TARGET_ID);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceConfig)
                .sourceType(DatabaseType.H2)
                .sourceSchema("PUBLIC")
                .sourceReader(reader)
                .sourceDialect(new GenericSqlDialect())
                .batchSize(config.getBatchSize() == null ? 100 : config.getBatchSize())
                .build();
    }

    private SyncConfig config(Set<String> tables, int batchSize) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        config.setRedisProjection(projection);
        config.setObjectSelectionExplicit(true);
        config.setTables(new LinkedHashSet<>(tables));
        config.setBatchSize(batchSize);
        return config;
    }

    private List<RedisMutation> mutations() {
        ArgumentCaptor<List<RedisMutation>> captor = captor();
        verify(writer, org.mockito.Mockito.atLeastOnce()).apply(eq(target), captor.capture());
        List<RedisMutation> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        return all;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<RedisMutation>> captor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static TableMeta table(String name, List<String> columns, List<String> primaryKeys) {
        TableMeta meta = new TableMeta();
        meta.setName(name);
        List<ColumnMeta> metas = new ArrayList<>();
        for (String column : columns) {
            ColumnMeta c = new ColumnMeta();
            c.setName(column);
            c.setJdbcType("ID".equals(column) || "LOG_ID".equals(column) ? Types.INTEGER : Types.VARCHAR);
            metas.add(c);
        }
        meta.setColumns(metas);
        meta.setPrimaryKeys(new ArrayList<>(primaryKeys));
        return meta;
    }
}
