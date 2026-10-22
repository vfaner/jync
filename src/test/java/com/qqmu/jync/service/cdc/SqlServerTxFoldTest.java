package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * P0-6:SQL Server CDC 事务内行事件的保序折叠。
 *
 * <p>源语义:同一事务(同 __$start_lsn)内按 __$seqval 顺序发生的事,目标端必须以
 * 同样的净效果落地。旧实现把 op 分进 upsertsByTx/deletesByTx 两个桶、flush 时先全部
 * upsert 再全部 delete,两个数据错乱后果:
 * <ul>
 *   <li>同 PK 先 DELETE 后 INSERT(如 MERGE/重建行)→ 先插后删,行丢失;</li>
 *   <li>op=3(update-before)被整个丢弃 → UPDATE 改 PK 时旧 PK 行永久残留(幽灵行)。</li>
 * </ul>
 *
 * <p>折叠规则与 MySQL CdcEventStreamHandler 的 FIX C 同一语义:同 PK 后到覆盖先到;
 * op=3 暂存为该 binding 的 before 镜像,遇到配对的 op=4 时比对 PK,变更则补一条对
 * before 的 delete。事务间按 txLsn 升序 flush(TreeMap),位点不回退。
 */
class SqlServerTxFoldTest {

    private static final String TX_A = "0x0000002857ae0258000a";
    private static final String TX_B = "0x0000002857ae0258000b";

    private static ColumnMeta column(String name, int jdbcType, boolean pk) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setPrimaryKey(pk);
        return column;
    }

    private static CdcTableBinding ordersBinding() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("name", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("id");
        return CdcTableBinding.of("dbo", "orders", meta, "orders_dst", null);
    }

    private static CdcTableBinding itemsBinding() {
        TableMeta meta = new TableMeta();
        meta.setName("items");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("qty", Types.INTEGER, false));
        meta.getPrimaryKeys().add("id");
        return CdcTableBinding.of("dbo", "items", meta, "items_dst", null);
    }

    private static Map<String, Object> row(long id, Object name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        return row;
    }

    /** 二进制主键表:SQL Server binary/varbinary 列经 ResultSet.getObject 回来就是 byte[]。 */
    private static CdcTableBinding assetsBinding() {
        TableMeta meta = new TableMeta();
        meta.setName("assets");
        meta.getColumns().add(column("id", Types.BINARY, true));
        meta.getColumns().add(column("name", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("id");
        return CdcTableBinding.of("dbo", "assets", meta, "assets_dst", null);
    }

    private static Map<String, Object> binaryRow(byte[] id, Object name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        return row;
    }

    private static List<Long> ids(List<Map<String, Object>> rows) {
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add((Long) row.get("id"));
        }
        return out;
    }

    private SqlServerTxFold.Buffer buffer(SqlServerTxFold fold, String tx, CdcTableBinding b) {
        return fold.ordered().get(tx).get(b);
    }

    @Test
    void deleteThenInsertOfSamePkKeepsTheRow() {
        // 同事务 DELETE(id=1) → INSERT(id=1):净效果是行存在。旧实现先 upsert 后
        // delete,行被抹掉。
        SqlServerTxFold fold = new SqlServerTxFold();
        CdcTableBinding b = ordersBinding();
        fold.accept(TX_A, b, 1, row(1L, "gone"));
        fold.accept(TX_A, b, 2, row(1L, "back"));

        SqlServerTxFold.Buffer buf = buffer(fold, TX_A, b);
        assertThat(ids(buf.upserts)).containsExactly(1L);
        assertThat(buf.deletes).isEmpty();
    }

    @Test
    void insertThenDeleteOfSamePkEndsDeleted() {
        SqlServerTxFold fold = new SqlServerTxFold();
        CdcTableBinding b = ordersBinding();
        fold.accept(TX_A, b, 2, row(1L, "temp"));
        fold.accept(TX_A, b, 1, row(1L, "temp"));

        SqlServerTxFold.Buffer buf = buffer(fold, TX_A, b);
        assertThat(buf.upserts).isEmpty();
        assertThat(ids(buf.deletes)).containsExactly(1L);
    }

    @Test
    void updateWithChangedPkDeletesOldRowAndUpsertsNew() {
        // op=3(before, id=1) → op=4(after, id=2):UPDATE 改了 PK。旧实现丢弃 op=3,
        // 目标端 id=1 永久残留成幽灵行。
        SqlServerTxFold fold = new SqlServerTxFold();
        CdcTableBinding b = ordersBinding();
        fold.accept(TX_A, b, 3, row(1L, "v"));
        fold.accept(TX_A, b, 4, row(2L, "v"));

        SqlServerTxFold.Buffer buf = buffer(fold, TX_A, b);
        assertThat(ids(buf.upserts)).containsExactly(2L);
        assertThat(ids(buf.deletes)).containsExactly(1L);
    }

    @Test
    void updateWithSamePkOnlyUpsertsAfterImage() {
        SqlServerTxFold fold = new SqlServerTxFold();
        CdcTableBinding b = ordersBinding();
        fold.accept(TX_A, b, 3, row(1L, "old"));
        fold.accept(TX_A, b, 4, row(1L, "new"));

        SqlServerTxFold.Buffer buf = buffer(fold, TX_A, b);
        assertThat(buf.upserts).hasSize(1);
        assertThat(buf.upserts.get(0).get("name")).isEqualTo("new");
        assertThat(buf.deletes).isEmpty();
    }

    /**
     * byte[] 主键:op=3/op=4 两幅镜像各是一个新数组,按引用比较必然「不等」。旧实现
     * 据此把主键没变的 UPDATE 当成主键变更,多补一条旧主键 delete;sink 先 upsert
     * 后 delete → 目标行被删。与 MySQL 端同一根因,同一修复(CdcPrimaryKey)。
     */
    @Test
    void binaryPkUpdateWithUnchangedKeyDoesNotDeleteTheRow() {
        SqlServerTxFold fold = new SqlServerTxFold();
        CdcTableBinding b = assetsBinding();
        fold.accept(TX_A, b, 3, binaryRow(new byte[] {1, 2, 3}, "old"));
        fold.accept(TX_A, b, 4, binaryRow(new byte[] {1, 2, 3}, "new"));

        SqlServerTxFold.Buffer buf = buffer(fold, TX_A, b);
        assertThat(buf.upserts).hasSize(1);
        assertThat(buf.upserts.get(0).get("name")).isEqualTo("new");
        assertThat(buf.deletes).isEmpty();
    }

    /** 二进制主键的 DELETE→INSERT 同事务折叠:净效果是行存在(REPLACE/MERGE 场景)。 */
    @Test
    void binaryPkDeleteThenInsertFoldsToTheInsert() {
        SqlServerTxFold fold = new SqlServerTxFold();
        CdcTableBinding b = assetsBinding();
        fold.accept(TX_A, b, 1, binaryRow(new byte[] {9}, "gone"));
        fold.accept(TX_A, b, 2, binaryRow(new byte[] {9}, "back"));

        SqlServerTxFold.Buffer buf = buffer(fold, TX_A, b);
        assertThat(buf.upserts).hasSize(1);
        assertThat(buf.deletes).isEmpty();
    }

    @Test
    void transactionsAreOrderedByLsnForMonotonicPositions() {
        // 跨 capture instance 合并后,flush 必须按 txLsn 升序 — 否则 advanceLsn 回退,
        // 崩溃重启后重放窗口错乱。
        SqlServerTxFold fold = new SqlServerTxFold();
        CdcTableBinding b = ordersBinding();
        fold.accept(TX_B, b, 2, row(2L, "later"));
        fold.accept(TX_A, b, 2, row(1L, "earlier"));

        assertThat(fold.ordered().keySet()).containsExactly(TX_A, TX_B);
    }

    @Test
    void foldingStaysWithinItsOwnBinding() {
        // 两张表同 PK 值互不干扰;before 镜像也不跨 binding 串台。
        SqlServerTxFold fold = new SqlServerTxFold();
        CdcTableBinding orders = ordersBinding();
        CdcTableBinding items = itemsBinding();
        Map<String, Object> itemRow = new LinkedHashMap<>();
        itemRow.put("id", 1L);
        itemRow.put("qty", 5);

        fold.accept(TX_A, orders, 1, row(1L, "x"));
        fold.accept(TX_A, items, 3, itemRow);
        fold.accept(TX_A, items, 4, itemRow);
        fold.accept(TX_A, orders, 2, row(1L, "y"));

        assertThat(ids(buffer(fold, TX_A, orders).upserts)).containsExactly(1L);
        assertThat(buffer(fold, TX_A, orders).deletes).isEmpty();
        assertThat(buffer(fold, TX_A, items).deletes).isEmpty();
        assertThat(buffer(fold, TX_A, items).upserts).hasSize(1);
    }

    @Test
    void multipleUpdatesOfSamePkCollapseToFinalState() {
        // UPDATE 链 id 1→2→3:源端终态是行 1、2 都不在,行 3 在。目标端可能原本
        // 就存有行 2(更早同步的),所以净效果必须是 upsert(3) + delete(1) + delete(2),
        // 与 MySQL 端 FIX C 的折叠语义一致(upserts 先应用、deletes 后应用)。
        SqlServerTxFold fold = new SqlServerTxFold();
        CdcTableBinding b = ordersBinding();
        fold.accept(TX_A, b, 3, row(1L, "v1"));
        fold.accept(TX_A, b, 4, row(2L, "v1"));
        fold.accept(TX_A, b, 3, row(2L, "v1"));
        fold.accept(TX_A, b, 4, row(3L, "v1"));

        SqlServerTxFold.Buffer buf = buffer(fold, TX_A, b);
        assertThat(ids(buf.upserts)).containsExactly(3L);
        assertThat(ids(buf.deletes)).containsExactly(1L, 2L);
    }
}
