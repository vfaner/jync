package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * One source transaction's folded rows, shared by the Oracle and PostgreSQL engines. The
 * folding rule is the MySQL handler's: a row's net effect is its LAST image, applied as
 * either an upsert or a delete, never both.
 */
class CdcTransactionBufferTest {

    private static CdcTableBinding binding(String table) {
        TableMeta meta = new TableMeta();
        meta.setName(table);
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        ColumnMeta name = new ColumnMeta();
        name.setName("name");
        name.setJdbcType(Types.VARCHAR);
        meta.getColumns().add(id);
        meta.getColumns().add(name);
        meta.getPrimaryKeys().add("id");
        return CdcTableBinding.of("APP", table, meta, table + "_dst", null);
    }

    private static Map<String, Object> row(long id, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        return row;
    }

    @Test
    void anUpsertCancelsABufferedDeleteOfTheSameKey() {
        CdcTableBinding orders = binding("orders");
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        tx.delete(orders, row(1L, "gone"));
        tx.upsert(orders, row(1L, "back"));

        assertThat(tx.upserts().get(orders)).extracting(r -> r.get("name"))
                .containsExactly("back");
        assertThat(tx.deletes()).doesNotContainKey(orders);
    }

    @Test
    void aDeleteCancelsABufferedUpsertOfTheSameKey() {
        CdcTableBinding orders = binding("orders");
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        tx.upsert(orders, row(1L, "was here"));
        tx.delete(orders, row(1L, "gone"));

        assertThat(tx.deletes().get(orders)).extracting(r -> r.get("id"))
                .containsExactly(1L);
        assertThat(tx.upserts()).doesNotContainKey(orders);
    }

    @Test
    void theSameKeyWrittenTwiceKeepsOnlyTheLastImage() {
        CdcTableBinding orders = binding("orders");
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        tx.upsert(orders, row(1L, "first"));
        tx.upsert(orders, row(1L, "last"));

        assertThat(tx.upserts().get(orders)).hasSize(1);
        assertThat(tx.upserts().get(orders).get(0)).containsEntry("name", "last");
    }

    @Test
    void netOrderIsTheOrderOfEachKeysLastOccurrence() {
        CdcTableBinding orders = binding("orders");
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        tx.upsert(orders, row(1L, "a"));
        tx.upsert(orders, row(2L, "b"));
        tx.upsert(orders, row(1L, "a2"));

        assertThat(tx.upserts().get(orders)).extracting(r -> r.get("id"))
                .containsExactly(2L, 1L);
    }

    @Test
    void bindingsAreFoldedIndependently() {
        CdcTableBinding orders = binding("orders");
        CdcTableBinding items = binding("items");
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        tx.upsert(orders, row(1L, "o"));
        tx.upsert(items, row(1L, "i"));

        assertThat(tx.upserts().keySet()).containsExactly(orders, items);
    }

    @Test
    void emptinessAndSizeCoverBothDirections() {
        CdcTableBinding orders = binding("orders");
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        assertThat(tx.isEmpty()).isTrue();
        assertThat(tx.size()).isZero();

        tx.delete(orders, row(1L, "gone"));
        assertThat(tx.isEmpty()).isFalse();
        assertThat(tx.size()).isEqualTo(1);

        tx.clear();
        assertThat(tx.isEmpty()).isTrue();
        assertThat(tx.upserts()).isEmpty();
        assertThat(tx.deletes()).isEmpty();
    }

    @Test
    void aBindingWithNoRowsOfOneDirectionIsAbsentFromThatMap() {
        CdcTableBinding orders = binding("orders");
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        tx.upsert(orders, row(1L, "only"));

        assertThat(tx.upserts().get(orders)).hasSize(1);
        assertThat(tx.deletes()).doesNotContainKey(orders);
        assertThat(tx.upserts().get(orders)).isInstanceOf(List.class);
    }

    // —— v1.9.0 YStream: SAVEPOINT / ROLLBACK TO SAVEPOINT ——

    @Test
    void aCopyIsIndependentOfTheBufferItWasTakenFrom() {
        // YStream 是唯一携带 SAVEPOINT 的流:回滚到保存点要把缓冲退回到快照时刻。
        // 折叠缓冲无法"撤销"最后一次覆盖,只能整份快照。
        CdcTableBinding orders = binding("orders");
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        tx.upsert(orders, row(1L, "before"));

        CdcTransactionBuffer snapshot = tx.copy();
        tx.upsert(orders, row(1L, "after"));
        tx.upsert(orders, row(2L, "new"));

        assertThat(snapshot.upserts().get(orders)).hasSize(1);
        assertThat(snapshot.upserts().get(orders).get(0)).containsEntry("name", "before");
        assertThat(snapshot.size()).isEqualTo(1);
        assertThat(tx.size()).isEqualTo(2);
    }

    @Test
    void restoringFromASnapshotDiscardsEverythingBufferedAfterIt() {
        CdcTableBinding orders = binding("orders");
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        tx.upsert(orders, row(1L, "before"));
        CdcTransactionBuffer snapshot = tx.copy();

        tx.upsert(orders, row(1L, "after"));
        tx.delete(orders, row(2L, "gone"));
        tx.restoreFrom(snapshot);

        assertThat(tx.size()).isEqualTo(1);
        assertThat(tx.upserts().get(orders).get(0)).containsEntry("name", "before");
        assertThat(tx.deletes()).isEmpty();
    }

    @Test
    void restoringFromAnEmptySnapshotEmptiesTheBuffer() {
        CdcTableBinding orders = binding("orders");
        CdcTransactionBuffer snapshot = new CdcTransactionBuffer();
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        tx.upsert(orders, row(1L, "after"));

        tx.restoreFrom(snapshot);

        assertThat(tx.isEmpty()).isTrue();
    }

    @Test
    void theSnapshotItselfSurvivesTheRestore() {
        // ROLLBACK TO SAVEPOINT 不弹出保存点:同一个保存点可以再回滚一次。
        CdcTableBinding orders = binding("orders");
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        tx.upsert(orders, row(1L, "before"));
        CdcTransactionBuffer snapshot = tx.copy();

        tx.upsert(orders, row(1L, "after-1"));
        tx.restoreFrom(snapshot);
        tx.upsert(orders, row(1L, "after-2"));
        tx.restoreFrom(snapshot);

        assertThat(tx.upserts().get(orders).get(0)).containsEntry("name", "before");
    }
}
