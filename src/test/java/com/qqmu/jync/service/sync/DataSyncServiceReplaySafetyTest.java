package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.service.cdc.LogCapture;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * Two "must not fail quietly" guarantees around the initial load and replays:
 *
 * <ul>
 *   <li>A key-only table (every column part of the primary key) has no UPDATE half in the
 *       emulated upsert, so a re-delivered row can only collide. Since a row holding that key
 *       is by definition identical to the rejected one, the collision is a no-op — throwing
 *       on it would wedge the table permanently.</li>
 *   <li>{@code truncateTarget} promising an empty table must keep that promise: when both
 *       TRUNCATE and the DELETE fallback fail, the caller has to hear about it instead of
 *       loading on top of rows the user asked to clear.</li>
 * </ul>
 */
class DataSyncServiceReplaySafetyTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:replay_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:replay_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        service = new DataSyncService(new SyncProperties());
        ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        exec(target, "DROP ALL OBJECTS");
        source.close();
        target.close();
    }

    @Test
    void redeliveredRowsInAKeyOnlyTableAreANoopNotAnError() throws SQLException {
        exec(source, "CREATE TABLE KEYONLY (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE KEYONLY (ID BIGINT PRIMARY KEY)");
        exec(source, "INSERT INTO KEYONLY VALUES (1), (2)");

        TableMeta table = new TableMeta();
        table.setName("KEYONLY");
        table.setPrimaryKeys(List.of("ID")); // without it the table looks keyless

        DataSyncService.TableSyncResult first = service.syncTable(source, target, table, IDENTITY,
                new SyncProgress(), ctx);
        assertThat(first.isSuccess()).isTrue();
        assertThat(count("KEYONLY")).isEqualTo(2);

        // Replay the same keys: a cursor below the delivered rows re-reads them, and the
        // key-only table can only answer with a unique conflict.
        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("0");

        DataSyncService.TableSyncResult replay =
                service.syncTable(source, target, table, IDENTITY, progress, ctx);

        assertThat(replay.isSuccess())
                .as("replayed key-only rows must converge, error was: %s", replay.getError())
                .isTrue();
        assertThat(replay.getNewCursorValue()).isEqualTo("2");
        assertThat(count("KEYONLY")).isEqualTo(2);
    }

    /**
     * 有主键的表每轮都会把安全余量里那一秒的行重发一遍（设计如此，upsert 吸收掉）。可吸收掉
     * 的重放不是"同步"：空闲项目上这条 INFO 会每表每秒刷一行 "Synced 1185 row(s)
     * (0 changed)"，用户看到的就是这个 —— 以为数据一直在变。兄弟链路 SyncTaskRunner 那句
     * "Sync of 'X' finished" 早就是 {@code result.hasChanges() || !isSuccess()} 才打，
     * 这里对齐同一个约定。
     */
    @Test
    void anAbsorbedReplayIsNotAnnouncedOnTheInfoChannel() throws SQLException {
        exec(source, "CREATE TABLE KEYONLY (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE KEYONLY (ID BIGINT PRIMARY KEY)");
        exec(source, "INSERT INTO KEYONLY VALUES (1), (2)");

        TableMeta table = new TableMeta();
        table.setName("KEYONLY");
        table.setPrimaryKeys(List.of("ID"));
        assertThat(service.syncTable(source, target, table, IDENTITY, new SyncProgress(), ctx)
                .isSuccess()).isTrue();

        // 游标停在已送过的行上：窗口把这两行重发一遍，而键已存在、值必然相同，目标一行都没动
        // （key-only 表连 UPDATE 都没有，插入冲突即等价）。
        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("0");

        ListAppender<ILoggingEvent> logs = LogCapture.attach(DataSyncService.class);
        try {
            DataSyncService.TableSyncResult replay =
                    service.syncTable(source, target, table, IDENTITY, progress, ctx);

            assertThat(replay.getRowsWritten()).as("重放确实又读了两行").isEqualTo(2);
            assertThat(replay.getRowsChanged()).as("目标一行都没动").isZero();
            assertThat(LogCapture.messagesAt(Level.INFO, logs))
                    .as("目标一行没动的重放不该出现在 INFO 上")
                    .noneMatch(message -> message.contains("Synced"));
        } finally {
            LogCapture.detach(DataSyncService.class, logs);
        }
    }

    @Test
    void sourceStatementsReceiveTheConfiguredQueryTimeout() throws SQLException {
        SyncProperties properties = new SyncProperties();
        properties.setQueryTimeoutSeconds(123);
        DataSyncService svc = new DataSyncService(properties);

        exec(source, "CREATE TABLE TIMEOUT_T (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE TIMEOUT_T (ID BIGINT PRIMARY KEY)");
        exec(source, "INSERT INTO TIMEOUT_T VALUES (1)");

        TableMeta table = new TableMeta();
        table.setName("TIMEOUT_T");
        table.setPrimaryKeys(List.of("ID"));

        TimeoutSpy spy = TimeoutSpy.wrap(source);
        DataSyncService.TableSyncResult result = svc.syncTable(
                spy.connection(), target, table, IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(spy.timeouts()).contains(123);
    }

    /** Connection proxy that records query timeouts set on statements it hands out. */
    private static final class TimeoutSpy {
        private final java.util.List<Integer> timeouts = new java.util.ArrayList<>();
        private final Connection connection;

        private TimeoutSpy(Connection delegate) {
            this.connection = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if ("createStatement".equals(method.getName())) {
                            return statementSpy(delegate.createStatement());
                        }
                        if ("prepareStatement".equals(method.getName())) {
                            return statementSpy(delegate.prepareStatement((String) args[0]));
                        }
                        return method.invoke(delegate, args);
                    });
        }

        static TimeoutSpy wrap(Connection connection) {
            return new TimeoutSpy(connection);
        }

        Connection connection() {
            return connection;
        }

        java.util.List<Integer> timeouts() {
            return timeouts;
        }

        private Statement statementSpy(Statement statement) {
            // A PreparedStatement must stay a PreparedStatement to the caller.
            Class<?>[] interfaces = statement instanceof PreparedStatement
                    ? new Class<?>[] {PreparedStatement.class}
                    : new Class<?>[] {Statement.class};
            return (Statement) java.lang.reflect.Proxy.newProxyInstance(
                    Statement.class.getClassLoader(), interfaces,
                    (proxy, method, args) -> {
                        if ("setQueryTimeout".equals(method.getName())) {
                            timeouts.add((Integer) args[0]);
                        }
                        return method.invoke(statement, args);
                    });
        }
    }

    @Test
    void aKeylessFullCompareTableSeedsOnceThenSkipsInsteadOfDuplicating() throws SQLException {
        CursorStrategy fullCompare = CursorStrategy.fullCompare("no usable cursor");

        exec(source, "CREATE TABLE LOGS (LINE VARCHAR(50))");
        exec(target, "CREATE TABLE LOGS (LINE VARCHAR(50))");
        exec(source, "INSERT INTO LOGS VALUES ('a'), ('b'), ('c'), ('d'), ('e')");

        TableMeta table = new TableMeta();
        table.setName("LOGS"); // deliberately no primary key

        SyncProgress progress = new SyncProgress();
        DataSyncService.TableSyncResult first =
                service.syncTable(source, target, table, fullCompare, progress, ctx);
        assertThat(first.isSuccess()).isTrue();
        assertThat(count("LOGS")).isEqualTo(5);

        // Simulate the engine persisting the outcome: initial load done, fc: fingerprint stored.
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(first.getNewCursorValue());

        DataSyncService.TableSyncResult second =
                service.syncTable(source, target, table, fullCompare, progress, ctx);

        assertThat(second.isSkipped()).isTrue();
        // Before the fix every cycle plain-inserted all five rows again: 10, 15, 20, ...
        assertThat(count("LOGS")).isEqualTo(5);
    }

    @Test
    void aLeaseLostAfterACommittedBatchAbortsAndKeepsOnlyThatBatch() throws SQLException {
        // Small batches force a commit (and hence a renewal) after every two rows. The emulated
        // upsert path is used because GenericSqlDialect has no native upsert.
        ctx = ctx.toBuilder().batchSize(2).lockRenewer(() -> false).build();

        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(source, "INSERT INTO ITEMS VALUES (1,'a'), (2,'b'), (3,'c'), (4,'d'), (5,'e')");

        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));

        assertThatThrownBy(() -> service.syncTable(source, target, table, IDENTITY,
                new SyncProgress(), ctx))
                .isInstanceOf(LockLostException.class);

        // Only the first, already-committed batch survives; the uncommitted tail is rolled back
        // rather than reaching the target, and (in the engine) the cursor is never advanced.
        assertThat(count("ITEMS")).isEqualTo(2);
    }

    @Test
    void aNullCursorRowOnAKeyedTableIsSeededAndKeptInLaterWindows() throws SQLException {
        // The cursor column is UPDATED_AT, which a legacy row leaves NULL.
        CursorStrategy timestamp =
                CursorStrategy.timestamp("UPDATED_AT", Types.TIMESTAMP, "update time");

        exec(source, "CREATE TABLE AUDIT (ID BIGINT PRIMARY KEY, LINE VARCHAR(20),"
                + " UPDATED_AT TIMESTAMP)");
        exec(target, "CREATE TABLE AUDIT (ID BIGINT PRIMARY KEY, LINE VARCHAR(20),"
                + " UPDATED_AT TIMESTAMP)");
        exec(source, "INSERT INTO AUDIT VALUES (1, 'a', TIMESTAMP '2026-01-01 00:00:00'),"
                + " (2, 'legacy', NULL)");

        TableMeta table = new TableMeta();
        table.setName("AUDIT");
        table.setPrimaryKeys(List.of("ID"));

        SyncProgress progress = new SyncProgress();
        DataSyncService.TableSyncResult seed =
                service.syncTable(source, target, table, timestamp, progress, ctx);
        assertThat(seed.isSuccess()).isTrue();
        // Before the fix the seed bounded the read by the watermark and the NULL row vanished.
        assertThat(count("AUDIT")).isEqualTo(2);

        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(seed.getNewCursorValue());
        exec(source, "INSERT INTO AUDIT VALUES (3, 'b', TIMESTAMP '2026-02-01 00:00:00')");

        DataSyncService.TableSyncResult next =
                service.syncTable(source, target, table, timestamp, progress, ctx);
        assertThat(next.isSuccess()).isTrue();
        // The new window row arrives and the NULL row is re-delivered (upsert absorbs it),
        // so the count grows by exactly one, never by two and never to four.
        assertThat(count("AUDIT")).isEqualTo(3);
    }

    @Test
    void aNullCursorRowOnAKeylessTableIsSeededOnceAndNotReinserted() throws SQLException {
        CursorStrategy timestamp =
                CursorStrategy.timestamp("UPDATED_AT", Types.TIMESTAMP, "update time");

        exec(source, "CREATE TABLE NOTES (LINE VARCHAR(20), UPDATED_AT TIMESTAMP)");
        exec(target, "CREATE TABLE NOTES (LINE VARCHAR(20), UPDATED_AT TIMESTAMP)");
        exec(source, "INSERT INTO NOTES VALUES ('a', TIMESTAMP '2026-01-01 00:00:00'),"
                + " ('legacy', NULL)");

        TableMeta table = new TableMeta();
        table.setName("NOTES"); // no primary key

        SyncProgress progress = new SyncProgress();
        DataSyncService.TableSyncResult seed =
                service.syncTable(source, target, table, timestamp, progress, ctx);
        assertThat(seed.isSuccess()).isTrue();
        assertThat(count("NOTES")).isEqualTo(2);
        // The keyless seed must store the watermark exactly: with the default 1s lag the
        // old code rewound it, and the very next window plain-inserted row 'a' again.
        String expectedWatermark = java.sql.Timestamp.valueOf("2026-01-01 00:00:00")
                .toInstant().toString();
        assertThat(seed.getNewCursorValue()).isEqualTo(expectedWatermark);

        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(seed.getNewCursorValue());
        exec(source, "INSERT INTO NOTES VALUES ('b', TIMESTAMP '2026-02-01 00:00:00')");

        DataSyncService.TableSyncResult next =
                service.syncTable(source, target, table, timestamp, progress, ctx);
        assertThat(next.isSuccess()).isTrue();
        // The NULL row stays out of the keyless window and the lag no longer replays 'a';
        // before the fix this was 4 (and grew by one more every cycle).
        assertThat(count("NOTES")).isEqualTo(3);
    }

    @Test
    void aTimeCursorColumnSyncsIncrementallyAcrossCycles() throws SQLException {
        // P0-4: 高水位线对 TIME 列错误地走 getTimestamp，存成 ISO instant，解析端只认
        // HH:mm:ss —— 每轮都报 "Unparseable high-watermark"，TIME 游标表永远同步不了。
        CursorStrategy timeCursor =
                CursorStrategy.timestamp("SLOT", Types.TIME, "explicit time cursor");

        exec(source, "CREATE TABLE SHIFTS (ID BIGINT PRIMARY KEY, SLOT TIME)");
        exec(target, "CREATE TABLE SHIFTS (ID BIGINT PRIMARY KEY, SLOT TIME)");
        exec(source, "INSERT INTO SHIFTS VALUES (1, TIME '08:00:00')");

        TableMeta table = new TableMeta();
        table.setName("SHIFTS");
        table.setPrimaryKeys(List.of("ID"));

        SyncProgress progress = new SyncProgress();
        DataSyncService.TableSyncResult seed =
                service.syncTable(source, target, table, timeCursor, progress, ctx);
        assertThat(seed.isSuccess())
                .as("TIME cursor seed failed: %s", seed.getError()).isTrue();
        assertThat(seed.getNewCursorValue()).isEqualTo("08:00:00");

        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(seed.getNewCursorValue());
        exec(source, "INSERT INTO SHIFTS VALUES (2, TIME '09:30:00')");

        DataSyncService.TableSyncResult next =
                service.syncTable(source, target, table, timeCursor, progress, ctx);
        assertThat(next.isSuccess())
                .as("TIME cursor incremental failed: %s", next.getError()).isTrue();
        assertThat(count("SHIFTS")).isEqualTo(2);
    }

    @Test
    void aDateCursorColumnKeepsWallDateAcrossCycles() throws SQLException {
        // P0-3 的 DATE 变体：旧实现把日期编成 UTC 零点 instant，再经时区解码可能错一天。
        CursorStrategy dateCursor =
                CursorStrategy.timestamp("EVENT_DAY", Types.DATE, "explicit date cursor");

        exec(source, "CREATE TABLE EVENTS (ID BIGINT PRIMARY KEY, EVENT_DAY DATE)");
        exec(target, "CREATE TABLE EVENTS (ID BIGINT PRIMARY KEY, EVENT_DAY DATE)");
        exec(source, "INSERT INTO EVENTS VALUES (1, DATE '2026-01-01')");

        TableMeta table = new TableMeta();
        table.setName("EVENTS");
        table.setPrimaryKeys(List.of("ID"));

        SyncProgress progress = new SyncProgress();
        DataSyncService.TableSyncResult seed =
                service.syncTable(source, target, table, dateCursor, progress, ctx);
        assertThat(seed.isSuccess()).as("DATE cursor seed failed: %s", seed.getError()).isTrue();
        assertThat(seed.getNewCursorValue()).isEqualTo("2026-01-01");

        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(seed.getNewCursorValue());
        exec(source, "INSERT INTO EVENTS VALUES (2, DATE '2026-02-01')");

        DataSyncService.TableSyncResult next =
                service.syncTable(source, target, table, dateCursor, progress, ctx);
        assertThat(next.isSuccess()).isTrue();
        assertThat(count("EVENTS")).isEqualTo(2);
    }

    @Test
    void theSafetyLagStillRewindsAKeyedTableWatermark() throws SQLException {
        // 对照用例：有主键时回退必须保留，迟到事务保护不能丢。
        CursorStrategy timestamp =
                CursorStrategy.timestamp("UPDATED_AT", Types.TIMESTAMP, "update time");

        exec(source, "CREATE TABLE AUDIT (ID BIGINT PRIMARY KEY, UPDATED_AT TIMESTAMP)");
        exec(target, "CREATE TABLE AUDIT (ID BIGINT PRIMARY KEY, UPDATED_AT TIMESTAMP)");
        exec(source, "INSERT INTO AUDIT VALUES (1, TIMESTAMP '2026-01-01 00:00:00')");

        TableMeta table = new TableMeta();
        table.setName("AUDIT");
        table.setPrimaryKeys(List.of("ID"));

        DataSyncService.TableSyncResult seed =
                service.syncTable(source, target, table, timestamp, new SyncProgress(), ctx);
        assertThat(seed.isSuccess()).isTrue();
        // 默认 lag 1000ms：存储游标比水位线早一秒（用与生产一致的时区换算推导期望值）。
        String expected = java.sql.Timestamp.valueOf("2026-01-01 00:00:00")
                .toInstant().minusMillis(1000).toString();
        assertThat(seed.getNewCursorValue()).isEqualTo(expected);
    }

    @Test
    void truncateEmptiesTheTargetOnTheHappyPath() throws SQLException {
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "INSERT INTO ITEMS VALUES (1, 'old')");

        TableMeta table = new TableMeta();
        table.setName("ITEMS");

        service.truncateTarget(target, table, ctx);

        assertThat(count("ITEMS")).isZero();
    }

    @Test
    void truncatingTheTargetDropsTheTablesChunkCheckpoints() throws SQLException {
        // An empty target plus a surviving checkpoint is the worst combination: the resumed
        // chunked load trusts its RANGE_DONE marker and skips the ranges it "already copied",
        // into a table the user just emptied. The truncate must invalidate the resume points.
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");

        ChunkCheckpointRepository checkpoints = mock(ChunkCheckpointRepository.class);
        DataSyncService svc = new DataSyncService(new SyncProperties(), checkpoints);

        Project project = new Project();
        project.setId(42L);
        SyncContext withProject = ctx.toBuilder().project(project).build();

        TableMeta table = new TableMeta();
        table.setName("ITEMS");

        svc.truncateTarget(target, table, withProject);

        verify(checkpoints).deleteByProjectIdAndTableName(42L, "ITEMS");
    }

    @Test
    void aTruncateThatFailedLeavesTheCheckpointsAlone() throws SQLException {
        // The cycle is about to fail and retry; the resume points still describe the rows
        // already committed to a table that was NOT emptied, so they remain valid.
        ChunkCheckpointRepository checkpoints = mock(ChunkCheckpointRepository.class);
        DataSyncService svc = new DataSyncService(new SyncProperties(), checkpoints);

        Project project = new Project();
        project.setId(42L);
        SyncContext withProject = ctx.toBuilder().project(project).build();

        TableMeta table = new TableMeta();
        table.setName("ITEMS");

        Connection dead = DriverManager.getConnection(
                "jdbc:h2:mem:replay_dead2;DB_CLOSE_DELAY=-1", "sa", "");
        dead.close();

        assertThatThrownBy(() -> svc.truncateTarget(dead, table, withProject))
                .isInstanceOf(SQLException.class);
        verify(checkpoints, org.mockito.Mockito.never())
                .deleteByProjectIdAndTableName(42L, "ITEMS");
    }

    @Test
    void aTruncateWhoseFallbackAlsoFailsThrowsInsteadOfPassingSilently() throws SQLException {
        TableMeta table = new TableMeta();
        table.setName("ITEMS");

        // On a dead connection both TRUNCATE and the DELETE fallback fail; the caller must
        // not proceed to load into a table that was never emptied.
        Connection dead = DriverManager.getConnection(
                "jdbc:h2:mem:replay_dead;DB_CLOSE_DELAY=-1", "sa", "");
        dead.close();

        assertThatThrownBy(() -> service.truncateTarget(dead, table, ctx))
                .isInstanceOf(SQLException.class);
    }

    /**
     * 一条被改过的行会停在游标窗口的最上沿（它的游标值就是水位线），安全余量又让水位线每轮
     * 回退，于是它每一轮都会被重发。通用（模拟 upsert）目标没有原生 upsert，写入是
     * UPDATE 再 INSERT，而 UPDATE 遇到值已经完全相同的行照样报 1 影响行 —— 每一轮都被记成
     * 一次更新，变更日志就把同一次修改反复记下去。现场复现的动作是「改主键 + 改更新时间」。
     */
    @Test
    void aReplayedRowOnAnEmulatedTargetIsNotCountedAgain() throws SQLException {
        CursorStrategy timestamp =
                CursorStrategy.timestamp("UPDATED_AT", Types.TIMESTAMP, "update time");

        exec(source, "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, NOTE VARCHAR(20),"
                + " UPDATED_AT TIMESTAMP)");
        exec(target, "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, NOTE VARCHAR(20),"
                + " UPDATED_AT TIMESTAMP)");
        exec(source, "INSERT INTO ORDERS VALUES (1, 'a', TIMESTAMP '2026-01-01 00:00:00')");

        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        table.setPrimaryKeys(List.of("ID"));

        SyncProgress progress = new SyncProgress();
        DataSyncService.TableSyncResult seed =
                service.syncTable(source, target, table, timestamp, progress, ctx);
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(seed.getNewCursorValue());

        // The reported edit: the key and the cursor column both move to the newest values, so
        // the row becomes the one every later window starts with.
        exec(source, "UPDATE ORDERS SET ID = 2,"
                + " UPDATED_AT = TIMESTAMP '2026-02-01 00:00:00' WHERE ID = 1");

        DataSyncService.TableSyncResult first =
                service.syncTable(source, target, table, timestamp, progress, ctx);
        assertThat(first.getRowsInserted()).isEqualTo(1);
        progress.setLastSyncValue(first.getNewCursorValue());

        // Nothing changed since. Before the fix each idle cycle reported "1 updated" and filed
        // a fresh change-log entry; the count must stay at zero while the row is replayed.
        for (int cycle = 0; cycle < 3; cycle++) {
            DataSyncService.TableSyncResult replay =
                    service.syncTable(source, target, table, timestamp, progress, ctx);
            assertThat(replay.getRowsChanged()).as("idle cycle %d", cycle).isZero();
            assertThat(count("ORDERS")).isEqualTo(2);
            progress.setLastSyncValue(replay.getNewCursorValue());
        }

        // An edit that really changes a value is still a change — the guard must not swallow
        // it, and it must survive the replay of the following cycle without repeating.
        exec(source, "UPDATE ORDERS SET NOTE = 'b',"
                + " UPDATED_AT = TIMESTAMP '2026-03-01 00:00:00' WHERE ID = 2");
        DataSyncService.TableSyncResult edited =
                service.syncTable(source, target, table, timestamp, progress, ctx);
        assertThat(edited.getRowsChanged()).isEqualTo(1);
        progress.setLastSyncValue(edited.getNewCursorValue());

        DataSyncService.TableSyncResult afterEdit =
                service.syncTable(source, target, table, timestamp, progress, ctx);
        assertThat(afterEdit.getRowsChanged()).isZero();
    }

    /**
     * 目标表有一列没法比较（CLOB）时，"值真的变了"这个条件必须整表退让，与原生 upsert 的规则
     * 一致：只守一半列会静默跳过"只有那一列变了"的行，而拿 CLOB 去比在多数产品上直接报错。
     * 退让的代价是重放行仍记一次更新——这是既有边界；同一张表必须照常同步、不报错。
     */
    @Test
    void aTableWithAnUncomparableColumnKeepsTheUnguardedFallback() throws SQLException {
        CursorStrategy timestamp =
                CursorStrategy.timestamp("UPDATED_AT", Types.TIMESTAMP, "update time");
        exec(source, "CREATE TABLE DOCS (ID BIGINT PRIMARY KEY, BODY CLOB,"
                + " UPDATED_AT TIMESTAMP)");
        exec(target, "CREATE TABLE DOCS (ID BIGINT PRIMARY KEY, BODY CLOB,"
                + " UPDATED_AT TIMESTAMP)");
        exec(source, "INSERT INTO DOCS VALUES (1, 'hello', TIMESTAMP '2026-01-01 00:00:00')");

        TableMeta table = new TableMeta();
        table.setName("DOCS");
        table.setPrimaryKeys(List.of("ID"));

        SyncProgress progress = new SyncProgress();
        DataSyncService.TableSyncResult seed =
                service.syncTable(source, target, table, timestamp, progress, ctx);
        assertThat(seed.getRowsInserted()).isEqualTo(1);
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(seed.getNewCursorValue());

        // The row sits at the window's top, so each cycle replays it. The guard stood down, so
        // the replay is still booked as a write — but it must be a write, not a failure.
        DataSyncService.TableSyncResult replay =
                service.syncTable(source, target, table, timestamp, progress, ctx);
        assertThat(replay.getRowsChanged()).isEqualTo(1);
        assertThat(count("DOCS")).isEqualTo(1);
    }

    private long count(String tableName) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
