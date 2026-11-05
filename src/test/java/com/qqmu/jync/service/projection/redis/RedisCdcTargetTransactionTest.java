package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * The Redis target transaction buffers one CDC transaction's mutations and hands them to
 * {@link RedisProjectionWriter} only when it is told to commit; nothing reaches Redis, and no
 * counter moves, before that.
 */
class RedisCdcTargetTransactionTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long TARGET_ID = 9L;

    private RedisTargetService targetService;
    private RedisProjectionWriter writer;
    private CdcRuntimeStats stats;
    private RedisTarget target;
    private Project project;
    private RedisProjectionConfig projection;
    private SyncConfig config;

    @BeforeEach
    void setUp() {
        targetService = mock(RedisTargetService.class);
        writer = mock(RedisProjectionWriter.class);
        stats = new CdcRuntimeStats();

        target = new RedisTarget();
        target.setId(TARGET_ID);
        target.setName("cache");
        target.setHost("127.0.0.1");
        target.setPort(6379);
        when(targetService.find(TARGET_ID)).thenReturn(Optional.of(target));

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("orders-project");
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(TARGET_ID);

        projection = RedisProjectionConfig.defaultFor(PROJECT_ID);
        config = new SyncConfig();
        config.setRedisProjection(projection);
    }

    @Test
    void upsertsAndDeletesAreBufferedUntilCommit() {
        RedisCdcTargetTransaction tx = transaction();

        tx.applyUpserts(binding("id", "name"), List.of(row("id", 1L, "name", "a")));
        tx.applyDeletes(binding("id", "name"), List.of(row("id", 1L, "name", null)));

        verifyNoInteractions(writer);

        tx.commit();

        List<RedisMutation> sent = captureMutations(1);
        assertThat(sent).extracting(RedisMutation::kind)
                .containsExactly(RedisMutation.Kind.SET, RedisMutation.Kind.DEL);
        assertThat(sent.get(0).key()).isEqualTo("jync:1:orders:1");
        assertThat(sent.get(0).jsonValue()).isEqualTo("{\"id\":1,\"name\":\"a\"}");
        assertThat(sent.get(1).key()).isEqualTo("jync:1:orders:1");
    }

    @Test
    void aFailedReplyIsThrownAndNothingIsReportedAsApplied() {
        doThrow(new RedisProjectionException("boom")).when(writer).apply(any(), anyList());
        RedisCdcTargetTransaction tx = transaction();
        tx.applyUpserts(binding("id", "name"), List.of(row("id", 1L, "name", "a")));

        assertThatThrownBy(tx::commit).isInstanceOf(RedisProjectionException.class);

        assertThat(stats.getRowsApplied()).isZero();
        tx.close();
    }

    @Test
    void statsAreUpdatedOnlyAfterCommit() {
        RedisCdcTargetTransaction tx = transaction();
        tx.applyUpserts(binding("id", "name"),
                List.of(row("id", 1L, "name", "a"), row("id", 2L, "name", "b")));
        tx.applyDeletes(binding("id", "name"), List.of(row("id", 2L, "name", null)));

        assertThat(stats.getRowsApplied()).isZero();

        tx.commit();

        assertThat(stats.getRowsApplied()).isEqualTo(3);
    }

    @Test
    void largeTransactionFlushesInChunksButPositionStillFollowsCommit() {
        projection.setMaxCommandsPerTransaction(2);
        RedisCdcTargetTransaction tx = transaction();

        tx.applyUpserts(binding("id", "name"), List.of(
                row("id", 1L, "name", "a"), row("id", 2L, "name", "b"),
                row("id", 3L, "name", "c"), row("id", 4L, "name", "d"),
                row("id", 5L, "name", "e")));

        verify(writer, times(2)).apply(eq(target), anyList());
        assertThat(stats.getRowsApplied()).isZero();

        tx.commit();

        ArgumentCaptor<List<RedisMutation>> captor = captor();
        verify(writer, times(3)).apply(eq(target), captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(2, 2, 1);
        assertThat(stats.getRowsApplied()).isEqualTo(5);
    }

    @Test
    void deleteUsesDelAndIsIdempotent() {
        RedisCdcTargetTransaction tx = transaction();

        tx.applyDeletes(binding("id", "name"), List.of(row("id", 7L, "name", null)));
        tx.applyDeletes(binding("id", "name"), List.of(row("id", 7L, "name", null)));
        tx.commit();

        List<RedisMutation> sent = captureMutations(1);
        assertThat(sent).extracting(RedisMutation::kind)
                .containsExactly(RedisMutation.Kind.DEL, RedisMutation.Kind.DEL);
        assertThat(sent).extracting(RedisMutation::key)
                .containsOnly("jync:1:orders:7");
    }

    @Test
    void columnCleanserIsAppliedBeforeSerialization() {
        ColumnRule rule = new ColumnRule();
        rule.setTable("orders");
        rule.setColumn("name");
        rule.setType(ColumnRuleType.UPPER);
        config.setColumnRules(new ArrayList<>(List.of(rule)));

        RedisCdcTargetTransaction tx = transaction();
        tx.applyUpserts(binding("id", "name"), List.of(row("id", 1L, "name", "ab")));
        tx.commit();

        assertThat(captureMutations(1).get(0).jsonValue())
                .isEqualTo("{\"id\":1,\"name\":\"AB\"}");
    }

    @Test
    void hashModeWritesHsetAndDeletesVanishedFields() {
        projection.setValueFormat(RedisValueFormat.HASH);
        RedisCdcTargetTransaction tx = transaction();

        tx.applyUpserts(binding("id", "name"), List.of(row("id", 1L, "name", null)));
        tx.commit();

        RedisMutation mutation = captureMutations(1).get(0);
        assertThat(mutation.kind()).isEqualTo(RedisMutation.Kind.HSET);
        assertThat(mutation.fields()).containsEntry("id", "1");
        assertThat(mutation.deleteFields()).containsExactly("name");
    }

    @Test
    void aDroppedHashColumnIsReportedAsSchemaDrift() {
        projection.setValueFormat(RedisValueFormat.HASH);
        RedisCdcTargetTransaction tx = transaction();
        tx.applyUpserts(binding("id", "name", "extra"),
                List.of(row("id", 1L, "name", "a", "extra", "x")));

        assertThatThrownBy(() -> tx.applyUpserts(binding("id", "name"),
                List.of(row("id", 1L, "name", "a"))))
                .isInstanceOf(RedisLayoutDriftException.class)
                .hasMessageContaining("CDC schema drift")
                .hasMessageContaining("extra");
        tx.close();
    }

    @Test
    void anAddedHashColumnDoesNotParkTheStream() {
        // A HASH projection writes the fields of this row only, so a column ADDED by a DDL
        // leaves no stale data behind: parking here would take a healthy stream down for
        // nothing. Only a vanished column leaves a field that cannot be removed.
        projection.setValueFormat(RedisValueFormat.HASH);
        RedisCdcTargetTransaction tx = transaction();
        tx.applyUpserts(binding("id", "name"), List.of(row("id", 1L, "name", "a")));
        tx.applyUpserts(binding("id", "name", "extra"),
                List.of(row("id", 1L, "name", "a", "extra", "x")));
        tx.commit();

        assertThat(captureMutations(1)).hasSize(2);
    }

    @Test
    void jsonModeAcceptsALayoutChange() {
        RedisCdcTargetTransaction tx = transaction();
        tx.applyUpserts(binding("id", "name"), List.of(row("id", 1L, "name", "a")));
        tx.applyUpserts(binding("id", "name", "extra"),
                List.of(row("id", 1L, "name", "a", "extra", "x")));
        tx.commit();

        assertThat(captureMutations(1)).hasSize(2);
    }

    @Test
    void aRowTheSerializerRejectsIsSkippedNotFatal() {
        RedisCdcTargetTransaction tx = transaction();
        tx.applyUpserts(binding("id", "name"),
                List.of(row("id", 1L, "name", new Object()), row("id", 2L, "name", "b")));
        tx.commit();

        assertThat(captureMutations(1)).hasSize(1);
        assertThat(stats.getRowsApplied()).isEqualTo(1);
    }

    @Test
    void aRowWithoutAKeyComponentIsSkippedNotFatal() {
        RedisCdcTargetTransaction tx = transaction();
        tx.applyUpserts(binding("id", "name"),
                List.of(row("id", null, "name", "a"), row("id", 2L, "name", "b")));
        tx.commit();

        List<RedisMutation> sent = captureMutations(1);
        assertThat(sent).extracting(RedisMutation::key).containsExactly("jync:1:orders:2");
        assertThat(stats.getRowsApplied()).isEqualTo(1);
    }

    @Test
    void closeWithoutCommitDiscardsTheBuffer() {
        RedisCdcTargetTransaction tx = transaction();
        tx.applyUpserts(binding("id", "name"), List.of(row("id", 1L, "name", "a")));

        tx.close();

        verify(writer, never()).apply(any(), anyList());
        assertThat(stats.getRowsApplied()).isZero();
    }

    @Test
    void aMissingTargetIsReportedAsSoonAsTheTransactionOpens() {
        when(targetService.find(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(this::transaction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(TARGET_ID));
    }

    // —— helpers ——

    private RedisCdcTargetTransaction transaction() {
        return new RedisCdcTargetTransaction(context(), targetService, writer, stats);
    }

    private SyncContext context() {
        return SyncContext.builder().project(project).config(config).build();
    }

    private List<RedisMutation> captureMutations(int times) {
        ArgumentCaptor<List<RedisMutation>> captor = captor();
        verify(writer, times(times)).apply(eq(target), captor.capture());
        List<RedisMutation> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        return all;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<RedisMutation>> captor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static CdcTableBinding binding(String... columns) {
        List<ColumnMeta> metas = new ArrayList<>();
        for (String name : columns) {
            ColumnMeta column = new ColumnMeta();
            column.setName(name);
            column.setJdbcType(Types.VARCHAR);
            metas.add(column);
        }
        metas.get(0).setPrimaryKey(true);
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.setColumns(metas);
        meta.setPrimaryKeys(List.of(columns[0]));
        return CdcTableBinding.of("shop", "orders", meta, "orders", null);
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }
}
