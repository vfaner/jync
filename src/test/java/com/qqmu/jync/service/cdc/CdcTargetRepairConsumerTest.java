package com.qqmu.jync.service.cdc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * How a consumer's run loop reacts to a missing target table.
 *
 * <p>Repair success must be invisible on the failure path: the table is back, so the consumer
 * keeps replaying from the same position — no ERROR hat, no failure row. A repair that did not
 * work (or an engine with no repair wired) must park exactly once, with the reason the repair
 * produced, instead of reconnecting forever.
 */
class CdcTargetRepairConsumerTest {

    private static final String H2_URL =
            "jdbc:h2:mem:cdc_repair_consumer;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    private Connection source;
    private CdcPositionStore positionStore;
    private DataSourceManager dataSourceManager;
    private SyncTaskStore taskStore;
    private SyncStateWriter stateWriter;
    private SyncProperties properties;
    private CdcRuntimeStats stats;
    private final AtomicInteger targetCycle = new AtomicInteger();

    private static Connection h2conn() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        }

        source = mock(Connection.class);
        Statement probeStmt = mock(Statement.class);
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        when(probeStmt.executeQuery(anyString())).thenReturn(emptyRs);
        when(source.createStatement()).thenReturn(probeStmt);

        positionStore = mock(CdcPositionStore.class);
        CdcPosition anchored = new CdcPosition();
        anchored.setProjectId(1L);
        anchored.setScn(1_000L);
        anchored.setBaselineDone(true);
        when(positionStore.find(1L)).thenReturn(java.util.Optional.of(anchored));

        dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getConnection(any())).thenReturn(source);

        taskStore = mock(SyncTaskStore.class);
        stateWriter = mock(SyncStateWriter.class);
        stats = new CdcRuntimeStats();
        properties = new SyncProperties();
        properties.getCdc().setReconnectBackoffMs(1L);
        properties.getCdc().setReconnectBackoffMaxMs(1L);
        properties.getCdc().getLogminer().setIdlePollMs(1);
        targetCycle.set(0);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void aSuccessfulRepairLetsTheStreamKeepReplayingWithoutParking() throws Exception {
        CdcTargetRepairService repair = mock(CdcTargetRepairService.class);
        when(repair.repair(any(), any())).thenReturn(new CdcTargetRepairService.Outcome(
                CdcTargetRepairService.Status.REPAIRED, List.of("ORDERS"),
                "recreated and reloaded [ORDERS]"));

        LogMinerCdcConsumer consumer = consumer(missingOnFirstTransaction(), repair, 8);
        consumer.run();

        verify(repair, atLeastOnce()).repair(any(), any());
        verify(taskStore, never()).recordCdcFailure(anyLong(), anyString());
        // The replay resumed from the same position and committed a transaction.
        verify(positionStore, atLeastOnce()).advanceScn(eq(1L), anyLong());
    }

    @Test
    void aRepairThatReachesItsCapParksTheProjectWithTheReason() throws Exception {
        CdcTargetRepairService repair = mock(CdcTargetRepairService.class);
        when(repair.repair(any(), any())).thenReturn(new CdcTargetRepairService.Outcome(
                CdcTargetRepairService.Status.CAP_REACHED, List.of(),
                "the target table was missing in 3 consecutive repair cycles (limit 3)"));

        LogMinerCdcConsumer consumer = consumer(missingOnFirstTransaction(), repair, 8);
        consumer.run();

        verify(taskStore).recordCdcFailure(eq(1L), contains("TARGET_TABLE_MISSING"));
        verify(taskStore).recordCdcFailure(eq(1L), contains("CAP_REACHED"));
        verify(stateWriter).recordLog(eq(1L), eq(ObjectType.PROJECT), anyString(),
                eq(ChangeType.ERROR), contains("CAP_REACHED"), eq(false), eq(0L));
    }

    /**
     * An engine built without the repair service (tests, hand-built consumers) must still stop
     * the reconnect loop — the step-1 behaviour — rather than replay the same absent table.
     */
    @Test
    void aConsumerWithoutTheRepairServiceParksInsteadOfReconnectingForever() throws Exception {
        LogMinerCdcConsumer consumer = consumer(missingOnFirstTransaction(), null, 8);
        consumer.run();

        verify(taskStore).recordCdcFailure(eq(1L), contains("TARGET_TABLE_MISSING"));
        verify(taskStore, never()).recordCdcFailure(eq(1L), contains("APPLY_STRIKE_OUT"));
    }

    // ── harness ───────────────────────────────────────────────────────────────

    /**
     * A target that refuses the first transaction because the table vanished, then works. Each
     * mining cycle opens exactly one target transaction, so the first failure is the first open.
     */
    private SyncContext missingOnFirstTransaction() throws SQLException {
        MetadataReader reader = mock(MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(ordersMeta());
        return SyncContext.builder()
                .config(new SyncConfig())
                .sourceConfig(new com.qqmu.jync.model.DatabaseConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(100)
                .sourceReader(reader)
                .connectionProvider(cfg -> {
                    int n = targetCycle.getAndIncrement();
                    if (n == 0) {
                        throw new CdcTargetTableMissingException("ORDERS", "ORDERS", null,
                                new SQLException("Table \"ORDERS\" not found"));
                    }
                    return h2conn();
                })
                .build();
    }

    private LogMinerCdcConsumer consumer(SyncContext ctx, CdcTargetRepairService repair,
                                         int lockGrants) {
        Project project = new Project();
        project.setId(1L);
        project.setName("lm-repair");
        LogMinerSessionFactory factory = (c, s, b) -> oneInsertSession();
        CdcTargetTransactionFactory transactions =
                new CdcTargetTransactionFactory(stats, properties.getQueryTimeoutSeconds());
        return new LogMinerCdcConsumer(project, boundedLock(lockGrants), ctx, positionStore,
                mock(CdcBaselineService.class), taskStore, stateWriter, dataSourceManager,
                stats, properties, factory, transactions, repair);
    }

    /** One INSERT row per cycle; fresh id/csn so a successful cycle really lands a row. */
    private LogMinerSession oneInsertSession() {
        int n = targetCycle.get() + 1;
        String redo = "insert into \"SRC\".\"ORDERS\"(\"ID\",\"NAME\") values ('"
                + n + "','row-" + n + "');";
        LogMinerSession.Row row = new LogMinerSession.Row("rs", 1_000L + n,
                "INSERT", "SRC", "ORDERS", redo, new Timestamp(System.currentTimeMillis()), 0);
        return new LogMinerSession(null, 1_000L, 1_000L, null, null) {
            private boolean consumed;

            @Override
            public java.util.List<LogMinerSession.Row> nextBatch() {
                if (consumed) {
                    return Collections.emptyList();
                }
                consumed = true;
                return Collections.singletonList(row);
            }

            @Override
            public long highWaterScn() {
                return 1_000L + n;
            }

            @Override
            public void close() {
            }
        };
    }

    private static TableMeta ordersMeta() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);
        meta.getColumns().add(id);
        meta.getColumns().add(name);
        meta.getPrimaryKeys().add("ID");
        return meta;
    }

    private static SyncLockService.LockHandle boundedLock(int grants) {
        AtomicInteger remaining = new AtomicInteger(grants);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenAnswer(inv -> remaining.decrementAndGet() >= 0);
        return lock;
    }
}
