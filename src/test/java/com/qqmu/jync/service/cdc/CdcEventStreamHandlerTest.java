package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Types;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;

import com.github.shyiko.mysql.binlog.BinaryLogClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

class CdcEventStreamHandlerTest {

    private CdcTableMetaSupplier supplier;
    private CdcRuntimeStats stats;
    private RecordingSink sink;
    private CdcEventStreamHandler handler;
    private SyncConfig config;

    static final class RecordingSink implements CdcEventStreamHandler.TransactionSink {
        final List<CdcEventStreamHandler.TableChange> changes = new ArrayList<>();
        final List<CdcPositionMark> marks = new ArrayList<>();
        SQLException failure;

        @Override
        public void apply(List<CdcEventStreamHandler.TableChange> changes, CdcPositionMark mark)
                throws SQLException {
            if (failure != null) {
                throw failure;
            }
            this.changes.addAll(changes);
            this.marks.add(mark);
        }
    }

    private static ColumnMeta column(String name, int jdbcType, boolean pk) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setPrimaryKey(pk);
        return column;
    }

    private static ColumnMeta column(String name, int jdbcType, String typeName, boolean pk) {
        ColumnMeta column = column(name, jdbcType, pk);
        column.setTypeName(typeName);
        return column;
    }

    private static CdcTableBinding ordersBinding() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("name", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("id");
        return CdcTableBinding.of("app", "orders", meta, "orders_dst", null);
    }

    /** 二进制主键表:UUID/摘要类主键在 MySQL 里就是 BINARY(16)/VARBINARY(n)。 */
    private static CdcTableBinding binaryKeyBinding() {
        TableMeta meta = new TableMeta();
        meta.setName("assets");
        meta.getColumns().add(column("id", Types.BINARY, "binary", true));
        meta.getColumns().add(column("name", Types.VARCHAR, "varchar", false));
        meta.getPrimaryKeys().add("id");
        return CdcTableBinding.of("app", "assets", meta, "assets_dst", null);
    }

    @BeforeEach
    void setUp() {
        supplier = mock(CdcTableMetaSupplier.class);
        when(supplier.binding("app", "orders")).thenReturn(ordersBinding());
        stats = new CdcRuntimeStats();
        sink = new RecordingSink();
        config = new SyncConfig();
        config.getTables().add("orders");
        handler = new CdcEventStreamHandler(config, "app", supplier,
                new CdcRowMapper(), stats, () -> "uuid:1-7", sink);
    }

    private static Event event(EventType type, Object data, long nextPosition) {
        EventHeaderV4 header = new EventHeaderV4();
        header.setEventType(type);
        header.setNextPosition(nextPosition);
        header.setTimestamp(1760100000000L);
        return new Event(header, (EventData) data);
    }

    private void tableMap(long tableId, String db, String table) {
        TableMapEventData data = new TableMapEventData();
        data.setTableId(tableId);
        data.setDatabase(db);
        data.setTable(table);
        handler.onEvent(event(EventType.TABLE_MAP, data, 100L));
    }

    private void write(long tableId, Serializable[]... rows) {
        WriteRowsEventData data = new WriteRowsEventData();
        data.setTableId(tableId);
        data.setRows(List.of(rows));
        handler.onEvent(event(EventType.EXT_WRITE_ROWS, data, 200L));
    }

    private void xid(long nextPosition) {
        XidEventData data = new XidEventData();
        data.setXid(99L);
        handler.onEvent(event(EventType.XID, data, nextPosition));
    }

    private static Serializable[] row(long id, String name) {
        return new Serializable[] {id, name.getBytes(StandardCharsets.UTF_8)};
    }

    @Test
    void writeRowsFlushOnXidWithPositionMark() {
        tableMap(11L, "app", "orders");
        write(11L, row(1L, "a"), row(2L, "b"));
        xid(500L);

        assertThat(sink.changes).hasSize(1);
        assertThat(sink.changes.get(0).upserts).hasSize(2);
        assertThat(sink.changes.get(0).binding.getTargetTable()).isEqualTo("orders_dst");
        CdcPositionMark mark = sink.marks.get(0);
        assertThat(mark.getPos()).isEqualTo(500L);
        assertThat(mark.getGtidSet()).isEqualTo("uuid:1-7");
        // rowsApplied 不在此断言:行计数由 applier(T8)在真正写目标库时增加,handler 只缓冲
    }

    @Test
    void aTransactionCrossingTheRowCapFailsFatalAndNeverReachesTheSink() {
        handler = new CdcEventStreamHandler(config, "app", supplier, new CdcRowMapper(), stats,
                () -> "uuid:1-7", sink, null, 0L, new CdcTransactionBudget(2L, 0L));
        tableMap(11L, "app", "orders");

        assertThatThrownBy(() -> write(11L, row(1L, "a"), row(2L, "b"), row(3L, "c")))
                .isInstanceOf(CdcTransactionBudget.CdcTransactionTooLargeException.class)
                .hasMessageContaining(CdcTransactionBudget.TOO_LARGE_MARKER);

        // The choke path stores the cause for the consumer's classifier: it must park
        // instead of reconnecting and replaying the same oversized transaction.
        assertThat(handler.takeFailure())
                .isInstanceOf(CdcTransactionBudget.CdcTransactionTooLargeException.class);
        assertThat(sink.changes).as("no partial transaction may reach the target").isEmpty();
    }

    @Test
    void theRowCapIsPerTransactionNotForTheWholeStream() {
        handler = new CdcEventStreamHandler(config, "app", supplier, new CdcRowMapper(), stats,
                () -> "uuid:1-7", sink, null, 0L, new CdcTransactionBudget(2L, 0L));
        tableMap(11L, "app", "orders");

        write(11L, row(1L, "a"), row(2L, "b"));
        xid(500L);
        write(11L, row(3L, "c"), row(4L, "d"));
        xid(600L);

        assertThat(sink.changes).hasSize(2);
        assertThat(sink.changes.get(1).upserts).hasSize(2);
    }

    @Test
    void otherSchemaAndUnselectedTablesIgnored() {
        tableMap(12L, "other_db", "orders");   // 库不匹配
        write(12L, row(1L, "x"));
        tableMap(13L, "app", "audit_log");     // 不在 config.tables
        write(13L, row(2L, "y"));
        xid(600L);
        assertThat(sink.changes).isEmpty();
    }

    @Test
    void noPkTableSkippedAndMarkedUnsupported() {
        TableMeta noPk = new TableMeta();
        noPk.setName("log_lines");
        noPk.getColumns().add(column("msg", Types.VARCHAR, false));
        when(supplier.binding("app", "log_lines")).thenReturn(
                CdcTableBinding.of("app", "log_lines", noPk, "log_lines", null));
        config.getTables().add("log_lines");
        tableMap(14L, "app", "log_lines");
        assertThat(stats.unsupportedTables()).contains("app.log_lines");

        WriteRowsEventData data = new WriteRowsEventData();
        data.setTableId(14L);
        data.setRows(List.<Serializable[]>of(new Serializable[] {"hi".getBytes(StandardCharsets.UTF_8)}));
        handler.onEvent(event(EventType.EXT_WRITE_ROWS, data, 700L));
        xid(701L);
        assertThat(sink.changes).isEmpty(); // 事件被跳过,不进事务
    }

    @Test
    void pkChangeEmitsDeletePlusUpsert() {
        // Review Focus #2:before=(1,"a") after=(2,"a") → delete 旧主键 + upsert 新行
        tableMap(11L, "app", "orders");
        UpdateRowsEventData data = new UpdateRowsEventData();
        data.setTableId(11L);
        data.setRows(List.of(new AbstractMap.SimpleEntry<Serializable[], Serializable[]>(
                row(1L, "a"), row(2L, "a"))));
        handler.onEvent(event(EventType.EXT_UPDATE_ROWS, data, 300L));
        xid(301L);

        assertThat(sink.changes).hasSize(1);
        assertThat(sink.changes.get(0).upserts).hasSize(1);
        assertThat(sink.changes.get(0).upserts.get(0)).containsEntry("id", 2L);
        assertThat(sink.changes.get(0).deletes).hasSize(1);
        assertThat(sink.changes.get(0).deletes.get(0)).containsEntry("id", 1L);
    }

    @Test
    void samePkUpdateOnlyUpserts() {
        tableMap(11L, "app", "orders");
        UpdateRowsEventData data = new UpdateRowsEventData();
        data.setTableId(11L);
        data.setRows(List.of(new AbstractMap.SimpleEntry<Serializable[], Serializable[]>(
                row(1L, "a"), row(1L, "b"))));
        handler.onEvent(event(EventType.EXT_UPDATE_ROWS, data, 310L));
        xid(311L);
        assertThat(sink.changes.get(0).deletes).isEmpty();
        assertThat(sink.changes.get(0).upserts).hasSize(1);
    }

    /**
     * byte[] 列(MySQL BINARY/VARBINARY/BLOB)是 CDC 里唯一没有值相等的 Java 类型:
     * before/after 两幅镜像各自由 binlog 反序列化出一个新数组,Objects.equals 按引用
     * 比较必然为 false。旧实现据此把「主键没变」误判成「主键变更」,于是每个普通
     * UPDATE 都多出一条 delete 旧主键的记录;sink 先 upsert 后 delete,目标行被删掉
     * ——静默丢数据,且与主键是否真的变了无关。
     */
    @Test
    void binaryPkUpdateWithUnchangedKeyDoesNotEmitADelete() {
        when(supplier.binding("app", "assets")).thenReturn(binaryKeyBinding());
        config.getTables().add("assets");
        tableMap(21L, "app", "assets");
        UpdateRowsEventData data = new UpdateRowsEventData();
        data.setTableId(21L);
        data.setRows(List.of(new AbstractMap.SimpleEntry<Serializable[], Serializable[]>(
                new Serializable[] {new byte[] {1, 2, 3}, "before".getBytes(StandardCharsets.UTF_8)},
                new Serializable[] {new byte[] {1, 2, 3}, "after".getBytes(StandardCharsets.UTF_8)})));
        handler.onEvent(event(EventType.EXT_UPDATE_ROWS, data, 340L));
        xid(341L);

        assertThat(sink.changes.get(0).upserts).hasSize(1);
        assertThat(sink.changes.get(0).deletes).isEmpty();
    }

    /**
     * REPLACE INTO 在 ROW binlog 里是 Delete_rows + Write_rows 同主键两条事件。折叠
     * (FIX C)靠 pkKey 的 List.equals 判同键,byte[] 同样按引用比较,折不掉 → sink
     * 先 upsert 后 delete,目标行被删掉。REPLACE 的语义是「留下新行」,不是删掉。
     */
    @Test
    void binaryPkReplaceFoldsToASingleUpsert() {
        when(supplier.binding("app", "assets")).thenReturn(binaryKeyBinding());
        config.getTables().add("assets");
        tableMap(21L, "app", "assets");
        DeleteRowsEventData delete = new DeleteRowsEventData();
        delete.setTableId(21L);
        delete.setRows(List.<Serializable[]>of(
                new Serializable[] {new byte[] {9}, "v1".getBytes(StandardCharsets.UTF_8)}));
        handler.onEvent(event(EventType.EXT_DELETE_ROWS, delete, 350L));
        WriteRowsEventData write = new WriteRowsEventData();
        write.setTableId(21L);
        write.setRows(List.<Serializable[]>of(
                new Serializable[] {new byte[] {9}, "v2".getBytes(StandardCharsets.UTF_8)}));
        handler.onEvent(event(EventType.EXT_WRITE_ROWS, write, 360L));
        xid(361L);

        assertThat(sink.changes.get(0).deletes).isEmpty();
        assertThat(sink.changes.get(0).upserts).hasSize(1);
    }

    @Test
    void deleteRowsBuffered() {
        tableMap(11L, "app", "orders");
        DeleteRowsEventData data = new DeleteRowsEventData();
        data.setTableId(11L);
        data.setRows(List.<Serializable[]>of(row(5L, "gone")));
        handler.onEvent(event(EventType.EXT_DELETE_ROWS, data, 320L));
        xid(321L);
        assertThat(sink.changes.get(0).deletes).hasSize(1);
        assertThat(sink.changes.get(0).deletes.get(0)).containsEntry("id", 5L);
    }

    @Test
    void ddlInvalidatesCacheAndDiscardsBuffer() {
        // Review Focus #1:流中 DDL → 缓存失效 + 当前缓冲丢弃(位点未推进,重连后整事务重放)。
        // 与 brief 测试稿的偏差:0.41.5 的 QueryEventData 没有 table 访问器(javap 实证,
        // 只有 sql+database),契约 5 又禁止 SQL 解析 → handler 传 (null, null) =「失效所有」;
        // Task 13 的 supplier 实现是 db==null → 清空全部缓存,若传 ("app", null) 会去 remove
        // 无意义的 "app.null" 键、留下陈旧 binding。断言语义不变:DDL → invalidate + 丢缓冲。
        tableMap(11L, "app", "orders");
        write(11L, row(1L, "a"));
        QueryEventData ddl = new QueryEventData();
        ddl.setSql("ALTER TABLE orders ADD COLUMN note VARCHAR(64)");
        ddl.setDatabase("app");
        handler.onEvent(event(EventType.QUERY, ddl, 330L));
        verify(supplier).invalidate(null, null);
        xid(331L);
        assertThat(sink.changes).isEmpty();
        assertThat(stats.snapshot().get("skippedDdl")).isEqualTo(1L);
    }

    @Test
    void beginAndCommitQueriesAreNoOps() {
        tableMap(11L, "app", "orders");
        write(11L, row(1L, "a"));
        QueryEventData begin = new QueryEventData();
        begin.setSql("BEGIN");
        handler.onEvent(event(EventType.QUERY, begin, 340L));
        xid(341L);
        assertThat(sink.changes).hasSize(1); // 缓冲未被 BEGIN 丢弃
        assertThat(stats.snapshot().get("skippedDdl")).isEqualTo(0L);
    }

    @Test
    void xaRollbackDiscardsBufferedRows() {
        // XA START → DML → XA END → XA PREPARE → XA ROLLBACK:行事件在 PREPARE 时已写入
        // binlog 并进 buffer,但事务已被源端回滚,ROLLBACK 之后不会再来该事务的 XID。
        // 若不清空,这些已回滚的行会搭上「下一个正常事务」的 XID 泄漏到目标端。
        tableMap(11L, "app", "orders");
        query("XA START 'gtrid1'");
        write(11L, row(1L, "a"));
        query("XA END 'gtrid1'");
        query("XA PREPARE 'gtrid1'");
        query("XA ROLLBACK 'gtrid1'");
        // 下一个正常事务:只带自己的行。
        write(11L, row(2L, "b"));
        xid(400L);
        assertThat(sink.changes).hasSize(1);
        assertThat(sink.changes.get(0).upserts).hasSize(1);
        assertThat(sink.changes.get(0).upserts.get(0)).containsEntry("id", 2L);
        assertThat(stats.snapshot().get("skippedDdl")).isEqualTo(0L);
    }

    @Test
    void xaCommitQueryItselfFlushesBufferedRows() {
        // 实测 MySQL 8.0.46 ROW binlog:两阶段 XA 的事件序列是
        // Query(XA START) → rows → Query(XA END) → XA_prepare → Query(XA COMMIT),
        // 之后【没有】XID 事件(XA PREPARE 时事务已持久化)。XA COMMIT 这个 Query
        // 就是提交点:必须在此 flush,否则行永远卡在 buffer,直到搭上下一个无关
        // 事务的 XID —— 延迟落库还错误地并入别的事务。
        tableMap(11L, "app", "orders");
        query("XA START 'gtrid2'");
        write(11L, row(1L, "a"));
        query("XA END 'gtrid2'");
        query("XA PREPARE 'gtrid2'");
        assertThat(sink.changes).isEmpty(); // PREPARE 不是提交:尚未可见,不能 flush
        query("XA COMMIT 'gtrid2'");
        assertThat(sink.changes).hasSize(1);
        assertThat(sink.changes.get(0).upserts.get(0)).containsEntry("id", 1L);
        assertThat(sink.marks).hasSize(1);
        assertThat(stats.snapshot().get("skippedDdl")).isEqualTo(0L);

        // 若某些 binlog 形态在 XA COMMIT 后仍跟一个 XID(如 one-phase 提交),
        // buffer 已空:不得重复输出或再打一条位点标记。
        xid(410L);
        assertThat(sink.changes).hasSize(1);
        assertThat(sink.marks).hasSize(1);
    }

    // —— XA 只保证「串行分支」:同一时刻只有一个分支在途/已准备,且分支准备期间没有
    // 别的会话碰被跟踪表。越界时 handler 只有一个按位置累积的 buffer,既无法把行按
    // 分支归属,也没有「最早未提交分支」的安全位点水位 —— 唯一安全的做法是致命停机
    // (ERROR 帽子,等人处置),绝不静默写错或丢行。———

    @Test
    void unrelatedTrackedTransactionWhileABranchIsPreparedIsFatal() {
        // MySQL 8.0.30+ 在 XA PREPARE 时就把分支写进 binlog,分支可以准备很久才提交,
        // 期间其它会话的事务照常提交、事件排在它后面。此刻 flush 会把这些未提交的
        // 分支行一起带进目标库(分支随后回滚 = 幽灵行);不 flush 又没有按分支拆分的
        // 位点语义(重启后可能丢行)。契约:致命停机。
        tableMap(11L, "app", "orders");
        query("XA START 'branchA'");
        write(11L, row(1L, "a"));
        query("XA END 'branchA'");
        query("XA PREPARE 'branchA'");

        write(11L, row(2L, "b"));                    // 无关的普通事务,写的是被跟踪表

        assertThatThrownBy(() -> xid(500L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(CdcEventStreamHandler.XA_ORDERING_MARKER);
        assertThat(sink.changes).isEmpty();          // 分支行绝不提前落库
        assertThat(handler.takeFailure())
                .hasMessageContaining(CdcEventStreamHandler.XA_ORDERING_MARKER);
    }

    @Test
    void aSecondPreparedBranchWhileOneIsPendingIsFatal() {
        // 两个分支同时在途时,buffer 里的行无法再按分支归属:先准备的分支回滚时会
        // 连同另一个分支的行一起清掉(源端已提交、目标端缺失)。契约:致命停机。
        tableMap(11L, "app", "orders");
        query("XA START 'branchA'");
        write(11L, row(1L, "a"));
        query("XA END 'branchA'");
        query("XA PREPARE 'branchA'");

        assertThatThrownBy(() -> query("XA START 'branchB'"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(CdcEventStreamHandler.XA_ORDERING_MARKER);
        assertThat(sink.changes).isEmpty();
    }

    @Test
    void xaCommitForAnotherBranchWhileOneIsPendingIsFatal() {
        // 分支身份对不上:这个 COMMIT 属于另一个(可能本连接开始前就准备好的)分支,
        // 拿它当提交点会把 branchA 的行提前落库。
        tableMap(11L, "app", "orders");
        query("XA START 'branchA'");
        write(11L, row(1L, "a"));
        query("XA END 'branchA'");
        query("XA PREPARE 'branchA'");

        assertThatThrownBy(() -> query("XA COMMIT 'branchB'"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(CdcEventStreamHandler.XA_ORDERING_MARKER);
        assertThat(sink.changes).isEmpty();
    }

    @Test
    void xaRollbackForAnotherBranchWhileOneIsPendingIsFatal() {
        // 回滚别的分支时清 buffer 会丢掉 branchA 的行(它若随后提交,目标端就缺数据)。
        tableMap(11L, "app", "orders");
        query("XA START 'branchA'");
        write(11L, row(1L, "a"));
        query("XA END 'branchA'");
        query("XA PREPARE 'branchA'");

        assertThatThrownBy(() -> query("XA ROLLBACK 'branchB'"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(CdcEventStreamHandler.XA_ORDERING_MARKER);
    }

    @Test
    void ddlWhileABranchIsPreparedIsFatal() {
        // DDL 会 invalidate 缓存并 clearBuffer —— 已准备分支的行就躺在这个 buffer 里,
        // 清掉之后它提交时无事可 flush(源端已提交、目标端永久缺失)。
        tableMap(11L, "app", "orders");
        query("XA START 'branchA'");
        write(11L, row(1L, "a"));
        query("XA END 'branchA'");
        query("XA PREPARE 'branchA'");

        QueryEventData ddl = new QueryEventData();
        ddl.setSql("ALTER TABLE other_table ADD COLUMN note VARCHAR(64)");
        ddl.setDatabase("app");
        assertThatThrownBy(() -> handler.onEvent(event(EventType.QUERY, ddl, 450L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(CdcEventStreamHandler.XA_ORDERING_MARKER);
        assertThat(sink.changes).isEmpty();
    }

    @Test
    void anOrdinaryTransactionWithoutTrackedRowsKeepsTheBranchAlive() {
        // 分支准备期间提交的普通事务如果没碰被跟踪表,buffer 里只有分支自己的行:
        // 这个 XID 什么都不做(位点也不能越过分支的行),分支随后正常提交。不能因为
        // 「有别的会话提交过」就误停一个本来安全的源。
        tableMap(11L, "app", "orders");
        query("XA START 'branchA'");
        write(11L, row(1L, "a"));
        query("XA END 'branchA'");
        query("XA PREPARE 'branchA'");

        xid(500L);                                   // 未跟踪表上的空事务
        assertThat(sink.changes).isEmpty();
        assertThat(sink.marks).isEmpty();

        query("XA COMMIT 'branchA'");
        assertThat(sink.changes).hasSize(1);
        assertThat(sink.changes.get(0).upserts).hasSize(1);
        assertThat(sink.changes.get(0).upserts.get(0)).containsEntry("id", 1L);
    }

    @Test
    void xaBranchIdComparesTheSameIdentifierAcrossQuoteStylesAndOptions() {
        // 分支身份校验只用来发现「提交/回滚的不是当前已准备的那个分支」,不解析完整
        // XA 语法:gtrid/bqual/formatID 原样保留,ONE PHASE / JOIN / RESUME 这类尾随
        // 关键字忽略;单双引号等价;解析不出来就返回 null(调用方跳过校验,不瞎猜)。
        assertThat(CdcEventStreamHandler.xaBranchId("XA START 'gtrid1'"))
                .isEqualTo(CdcEventStreamHandler.xaBranchId("XA COMMIT 'gtrid1'"));
        assertThat(CdcEventStreamHandler.xaBranchId("XA START 'it''s'"))
                .isEqualTo(CdcEventStreamHandler.xaBranchId("XA COMMIT 'it''s'"))
                .isNotEqualTo(CdcEventStreamHandler.xaBranchId("XA START 'it'"));
        assertThat(CdcEventStreamHandler.xaBranchId("XA PREPARE 'g','b',42"))
                .isEqualTo("'g','b',42");
        assertThat(CdcEventStreamHandler.xaBranchId("XA COMMIT 'g','b',42 ONE PHASE"))
                .isEqualTo("'g','b',42");
        assertThat(CdcEventStreamHandler.xaBranchId("XA START 'g' JOIN"))
                .isEqualTo("'g'");
        assertThat(CdcEventStreamHandler.xaBranchId("XA RECOVER")).isNull();
        assertThat(CdcEventStreamHandler.xaBranchId("XA RECOVER CONVERT XID")).isNull();
    }

    @Test
    void xaBranchIdTreatsSingleAndDoubleQuotesAsTheSameBranch() {
        // 应用完全可能用双引号写 XA PREPARE、用单引号写 XA COMMIT(不同客户端、不同
        // 代码路径、连接开了 ANSI_QUOTES 与否)。旧实现把引号字符原样留在 id 里,两个
        // 拼写永远不相等 —— requirePreparedBranch 于是把同一个分支判成「提交的不是
        // 已准备的那个」,项目以 XA_ORDERING 永久 park。
        assertThat(CdcEventStreamHandler.xaBranchId("XA PREPARE \"gtrid1\""))
                .isEqualTo(CdcEventStreamHandler.xaBranchId("XA COMMIT 'gtrid1'"));
        assertThat(CdcEventStreamHandler.xaBranchId("XA PREPARE \"g\",\"b\",42"))
                .isEqualTo(CdcEventStreamHandler.xaBranchId("XA COMMIT 'g','b',42"));
        // 双引号值里嵌单引号,与单引号写法里的双写转义是同一个值。
        assertThat(CdcEventStreamHandler.xaBranchId("XA PREPARE \"it's\""))
                .isEqualTo(CdcEventStreamHandler.xaBranchId("XA COMMIT 'it''s'"));
        // 汇合后的 id 必须仍然无歧义:一个含 "','" 的 gtrid 不能和两个分量撞车。
        assertThat(CdcEventStreamHandler.xaBranchId("XA PREPARE 'a'',''b'"))
                .isNotEqualTo(CdcEventStreamHandler.xaBranchId("XA COMMIT 'a','b'"));
    }

    private void query(String sql) {
        QueryEventData data = new QueryEventData();
        data.setSql(sql);
        data.setDatabase("app");
        handler.onEvent(event(EventType.QUERY, data, 300L));
    }

    @Test
    void rotateUpdatesFileInMark() {
        tableMap(11L, "app", "orders");
        RotateEventData rotate = new RotateEventData();
        rotate.setBinlogFilename("mysql-bin.000008");
        rotate.setBinlogPosition(4L);
        handler.onEvent(event(EventType.ROTATE, rotate, 350L));
        write(11L, row(1L, "a"));
        xid(351L);
        assertThat(sink.marks.get(0).getFile()).isEqualTo("mysql-bin.000008");
    }

    @Test
    void unmappableRowSkippedButTransactionContinues() {
        // 毒丸行:mapper 抛异常 → 该行计入 unmappable,事务其余行照常应用(B5:不静默、不卡死)。
        // 与 brief 测试稿的偏差:原稿把 TABLE_MAP 喂给局部 handler h、却用 write()/xid() 助手
        // 把行事件喂给 setUp 的 handler 字段(两者 bindings 缓存互不可见,unmappable 永远为 0)。
        // 最小修正:重建 handler 字段,后续事件全走新 handler;断言不变。
        CdcTableMetaSupplier failing = mock(CdcTableMetaSupplier.class);
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("weird", Types.STRUCT, "geometry", false)); // 映射不了
        meta.getPrimaryKeys().add("id");
        when(failing.binding("app", "orders")).thenReturn(
                CdcTableBinding.of("app", "orders", meta, "orders", null));
        handler = new CdcEventStreamHandler(config, "app", failing,
                new CdcRowMapper(), stats, () -> null, sink);
        tableMap(11L, "app", "orders");
        write(11L, row(1L, "a")); // 第二列是 VARCHAR 字节但元数据说 STRUCT → 抛
        xid(360L);
        assertThat(stats.snapshot().get("unmappable")).isEqualTo(1L);
        assertThat(sink.changes).isEmpty(); // 唯一一行被跳过 → 无变更可应用
    }

    @Test
    void sinkFailureKeepsBufferForReplay() {
        // 目标库写失败:位点不推进、缓冲保留 → consumer 重连后同一事务重放(幂等)。
        // 实现者注②:sink 的 SQLException 包成 RuntimeException rethrow(触发 client 断连,
        // consumer 分类退避重连),故第一个 xid 用 assertThatThrownBy 包住;断言语义不变。
        sink.failure = new SQLException("target down");
        tableMap(11L, "app", "orders");
        write(11L, row(1L, "a"));
        assertThatThrownBy(() -> xid(370L))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class);
        sink.failure = null;
        xid(371L); // 下一个边界:缓冲(仍含 row 1)再次提交
        assertThat(sink.changes).hasSize(1);
        assertThat(sink.marks.get(0).getPos()).isEqualTo(371L);
    }

    @Test
    void xidWithoutNextPositionFallsBackToLastKnownPosition() {
        // 契约 9:每个 nextPosition>0 的事件都更新 lastNextPosition;XID 头自身没有
        // nextPosition(=0)时回退到最后已知位点,位点标记绝不带 0 出去。
        tableMap(11L, "app", "orders"); // nextPosition 100
        write(11L, row(1L, "a"));       // nextPosition 200
        xid(0L);
        assertThat(sink.marks.get(0).getPos()).isEqualTo(200L);
    }

    // —— FIX C(评审 Important,T9 计划内缺陷):同事务内同 PK 必须 last-op-wins 折叠 ——
    // sink 固定先 upserts 后 deletes(该顺序让 PK 链 A→B、B→C 安全,不许动);不折叠时
    // REPLACE INTO 已有 PK(Delete_rows+Write_rows 同 XID)会先 upsert 再 DELETE → 静默丢行。

    private void delete(long tableId, Serializable... row) {
        DeleteRowsEventData data = new DeleteRowsEventData();
        data.setTableId(tableId);
        data.setRows(List.<Serializable[]>of((Serializable[]) row));
        handler.onEvent(event(EventType.EXT_DELETE_ROWS, data, 400L));
    }

    private void update(long tableId, Serializable[] before, Serializable[] after, long pos) {
        UpdateRowsEventData data = new UpdateRowsEventData();
        data.setTableId(tableId);
        data.setRows(List.of(new AbstractMap.SimpleEntry<>(before, after)));
        handler.onEvent(event(EventType.EXT_UPDATE_ROWS, data, pos));
    }

    @Test
    void replaceSamePkKeepsOnlyUpsert() {
        // REPLACE INTO 已存在的 PK:同 XID 里 Delete_rows(p) + Write_rows(p)
        tableMap(11L, "app", "orders");
        delete(11L, row(1L, "old"));
        write(11L, row(1L, "new"));
        xid(401L);

        assertThat(sink.changes).hasSize(1);
        assertThat(sink.changes.get(0).upserts).hasSize(1);
        assertThat(sink.changes.get(0).upserts.get(0))
                .containsEntry("id", 1L).containsEntry("name", "new"); // 活下来的是新行
        assertThat(sink.changes.get(0).deletes).isEmpty();             // 旧 delete 被折叠掉
    }

    @Test
    void insertThenDeleteSamePkKeepsOnlyDelete() {
        // 同 XID 里插入又删除:净效果是删除,upsert 必须被折叠掉
        tableMap(11L, "app", "orders");
        write(11L, row(2L, "tmp"));
        delete(11L, row(2L, "tmp"));
        xid(411L);

        assertThat(sink.changes).hasSize(1);
        assertThat(sink.changes.get(0).upserts).isEmpty();
        assertThat(sink.changes.get(0).deletes).hasSize(1);
        assertThat(sink.changes.get(0).deletes.get(0)).containsEntry("id", 2L);
    }

    // —— 裁定 R1(choke 路径):0.41.5 的 notifyEventListeners 吞掉监听器异常(打
    // "choked on" 日志后 goto 继续循环),rethrow 永远断不了连接 —— handler 必须自己
    // 存失败、自己断连,消费者靠 takeFailure() 拿到真因。——

    @Test
    void chokeStoresFailureForConsumerAndDisconnectsClient() throws Exception {
        BinaryLogClient client = mock(BinaryLogClient.class);
        handler.attachClient(client);
        sink.failure = new SQLException("target down");
        tableMap(11L, "app", "orders");
        write(11L, row(1L, "a"));
        // 直接调用方(单测)仍看到 rethrow;真客户端里这一抛被库吞掉,断连已由 handler 完成
        assertThatThrownBy(() -> xid(380L))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class);
        Throwable stored = handler.takeFailure();
        assertThat(stored).isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class)
                .hasMessageContaining("CDC transaction flush failed");
        assertThat(handler.takeFailure()).isNull();     // take 即清,绝不跨连接残留
        verify(client).disconnect();                    // 监听器线程上强制断连
    }

    @Test
    void chokeKeepsOriginalFailureWhenDisconnectAlsoFails() throws Exception {
        BinaryLogClient client = mock(BinaryLogClient.class);
        doThrow(new IOException("socket already gone")).when(client).disconnect();
        handler.attachClient(client);
        sink.failure = new SQLException("target down");
        tableMap(11L, "app", "orders");
        write(11L, row(1L, "a"));
        assertThatThrownBy(() -> xid(381L))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class);   // 断连失败不得顶替真因
        assertThat(handler.takeFailure()).hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void pkChainFoldsToLastOps() {
        // PK 链:UPDATE A→B 再 UPDATE B→C(同 XID)→ 终态只有 C:
        // upserts=[C];deletes=[A,B](A 来自第一条 before-image,B 来自第二条 before-image,
        // B 的 upsert 被第二条的 delete(B) 折叠掉)
        tableMap(11L, "app", "orders");
        update(11L, row(1L, "a"), row(2L, "a"), 420L);
        update(11L, row(2L, "a"), row(3L, "a"), 421L);
        xid(422L);

        assertThat(sink.changes).hasSize(1);
        assertThat(sink.changes.get(0).upserts).hasSize(1);
        assertThat(sink.changes.get(0).upserts.get(0)).containsEntry("id", 3L);
        assertThat(sink.changes.get(0).deletes).hasSize(2);
        assertThat(sink.changes.get(0).deletes.get(0)).containsEntry("id", 1L);
        assertThat(sink.changes.get(0).deletes.get(1)).containsEntry("id", 2L);
    }
}
