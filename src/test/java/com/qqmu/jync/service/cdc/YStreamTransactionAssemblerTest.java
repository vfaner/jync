package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * Transaction assembly for the YStream stream: begin/commit boundaries and the savepoint
 * stack.
 *
 * <p>The buffer folds rows (last write wins per primary key), which is why a savepoint cannot
 * simply "remember an index": rolling back has to restore a snapshot of the folded state, and
 * restoring must not consume the savepoint — {@code ROLLBACK TO SAVEPOINT s} keeps {@code s}.
 */
class YStreamTransactionAssemblerTest {

    private static final String TABLE = "ORDERS";

    private YStreamTransactionAssembler assembler;
    private CdcTableBinding binding;

    @BeforeEach
    void setUp() {
        assembler = new YStreamTransactionAssembler();
        binding = binding();
    }

    /** A binding that only carries the PK name — enough for folding. */
    private static CdcTableBinding binding() {
        TableMeta meta = new TableMeta();
        meta.setName(TABLE);
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        meta.getColumns().add(id);
        meta.getPrimaryKeys().add("ID");
        return CdcTableBinding.of("APP", TABLE, meta, TABLE, null);
    }

    private static Map<String, Object> row(Object id, String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", id);
        row.put("NOTE", note);
        return row;
    }

    @Test
    void aFreshTransactionIsEmpty() {
        assertThat(assembler.isEmpty()).isTrue();
    }

    @Test
    void beginOnACleanTransactionReportsNothingDiscarded() {
        assertThat(assembler.begin()).isFalse();
    }

    @Test
    void aSecondBeginDiscardsTheUncommittedRowsAndSaysSo() {
        assembler.begin();
        assembler.upsert(binding, row(1, "a"));

        assertThat(assembler.begin()).isTrue();
        assertThat(assembler.isEmpty()).isTrue();
    }

    @Test
    void takeReturnsEverythingBufferedAndLeavesAnEmptyTransaction() {
        assembler.begin();
        assembler.upsert(binding, row(1, "a"));
        assembler.delete(binding, row(2, null));

        CdcTransactionBuffer taken = assembler.take();

        assertThat(taken.upserts()).hasSize(1);
        assertThat(taken.deletes()).hasSize(1);
        assertThat(assembler.isEmpty()).isTrue();
    }

    @Test
    void aSavepointRollsBackOnlyTheRowsAfterIt() {
        assembler.begin();
        assembler.upsert(binding, row(1, "before"));
        assembler.savepoint();
        assembler.upsert(binding, row(2, "after"));

        assembler.rollbackToSavepoint();

        CdcTransactionBuffer taken = assembler.take();
        assertThat(taken.upserts()).hasSize(1);
        assertThat(taken.upserts().get(binding)).extracting(r -> r.get("ID"))
                .containsExactly(1);
    }

    @Test
    void rollingBackToTheSameSavepointTwiceIsRepeatable() {
        assembler.begin();
        assembler.upsert(binding, row(1, "kept"));
        assembler.savepoint();
        assembler.upsert(binding, row(2, "dropped"));

        assembler.rollbackToSavepoint();
        assembler.upsert(binding, row(3, "also dropped"));
        assembler.rollbackToSavepoint();

        assertThat(assembler.take().upserts().get(binding))
                .extracting(r -> r.get("ID")).containsExactly(1);
    }

    /**
     * Known limitation, pinned so it cannot change silently: the stream's SAVEPOINT and
     * ROLLBACK_TO_SAVEPOINT LCRs carry no savepoint name, so a rollback can only mean "the
     * innermost savepoint". Nested savepoints therefore restore the inner snapshot even
     * when the source rolled back to an outer one — the rows of the outer window survive.
     */
    @Test
    void aRollbackWithoutANameAlwaysRestoresTheInnermostSavepoint() {
        assembler.begin();
        assembler.upsert(binding, row(1, "outer"));
        assembler.savepoint();
        assembler.upsert(binding, row(2, "inner"));
        assembler.savepoint();
        assembler.upsert(binding, row(3, "dropped"));

        assembler.rollbackToSavepoint();
        assembler.rollbackToSavepoint();

        assertThat(assembler.take().upserts().get(binding))
                .extracting(r -> r.get("ID")).containsExactly(1, 2);
    }

    @Test
    void aSecondSavepointOnTopOfTheFirstKeepsTheLaterRows() {
        assembler.begin();
        assembler.upsert(binding, row(1, "kept"));
        assembler.savepoint();
        assembler.upsert(binding, row(2, "kept too"));
        assembler.savepoint();

        assembler.rollbackToSavepoint();

        assertThat(assembler.take().upserts().get(binding))
                .extracting(r -> r.get("ID")).containsExactly(1, 2);
    }

    @Test
    void aRollbackWithoutASavepointChangesNothing() {
        assembler.begin();
        assembler.upsert(binding, row(1, "kept"));

        assembler.rollbackToSavepoint();

        assertThat(assembler.take().upserts().get(binding)).hasSize(1);
    }

    /**
     * The snapshot cost is the whole folded buffer, not one row: the caller has to know how
     * much it just duplicated so it can charge the transaction budget for the memory it now
     * holds.
     */
    @Test
    void aSavepointReportsHowManyRowsItSnapshotted() {
        assembler.begin();
        assembler.upsert(binding, row(1, "a"));
        assembler.upsert(binding, row(2, "b"));

        assertThat(assembler.savepoint()).isEqualTo(2);

        assembler.upsert(binding, row(3, "c"));
        assertThat(assembler.savepoint()).isEqualTo(3);
    }

    @Test
    void aSavepointOnAnEmptyBufferReportsNothing() {
        assembler.begin();
        assertThat(assembler.savepoint()).isZero();
    }

    @Test
    void savepointsDoNotSurviveTheCommit() {
        assembler.begin();
        assembler.savepoint();
        assembler.take();

        // A stale savepoint from the previous transaction would restore an empty snapshot
        // over rows of the next one — silently dropping them.
        assembler.begin();
        assembler.upsert(binding, row(5, "next"));
        assembler.rollbackToSavepoint();

        assertThat(assembler.take().upserts().get(binding)).hasSize(1);
    }
}
