package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.projection.redis.RedisCdcTargetTransaction;
import com.qqmu.jync.service.projection.redis.RedisLayoutDriftException;
import com.qqmu.jync.service.projection.redis.RedisProjectionWriter;
import com.qqmu.jync.service.projection.redis.RedisTargetService;
import com.qqmu.jync.service.projection.redis.RedisValueFormat;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * The factory is the one place that knows a project's target kind. Until the Redis
 * transaction exists it must refuse a cache target instead of silently borrowing a JDBC
 * connection — a wrong guess here would write rows nowhere and still advance the position.
 */
class CdcTargetTransactionFactoryTest {

    private static Project project(SyncTargetKind kind) {
        Project project = new Project();
        project.setId(1L);
        project.setName("cdc");
        project.setTargetKind(kind);
        return project;
    }

    private static SyncContext context(Connection target) {
        return SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .connectionProvider(config -> target)
                .build();
    }

    @Test
    void factoryReturnsJdbcForJdbcProjects() throws Exception {
        Connection target = mock(Connection.class);
        when(target.getAutoCommit()).thenReturn(true);
        CdcTargetTransactionFactory factory =
                new CdcTargetTransactionFactory(new CdcRuntimeStats(), 0);

        CdcTargetTransaction tx = factory.open(project(SyncTargetKind.JDBC), context(target));
        assertThat(tx).isInstanceOf(JdbcCdcTargetTransaction.class);
        tx.close();

        verify(target).setAutoCommit(false);
        verify(target).close();
    }

    @Test
    void aProjectWithNoTargetKindIsJdbcLikeEveryExistingProject() throws Exception {
        Connection target = mock(Connection.class);
        when(target.getAutoCommit()).thenReturn(true);
        CdcTargetTransactionFactory factory =
                new CdcTargetTransactionFactory(new CdcRuntimeStats(), 0);

        CdcTargetTransaction tx = factory.open(project(null), context(target));
        assertThat(tx).isInstanceOf(JdbcCdcTargetTransaction.class);
        tx.close();
    }

    @Test
    void factoryReturnsRedisForRedisProjects() throws Exception {
        RedisTargetService targets = mock(RedisTargetService.class);
        RedisTarget target = new RedisTarget();
        target.setId(9L);
        target.setHost("127.0.0.1");
        target.setPort(6379);
        when(targets.find(9L)).thenReturn(Optional.of(target));

        Project redis = project(SyncTargetKind.REDIS);
        redis.setTargetRedisId(9L);
        SyncConfig config = new SyncConfig();
        config.setRedisProjection(RedisProjectionConfig.defaultFor(1L));
        SyncContext ctx = SyncContext.builder().project(redis).config(config).build();

        CdcTargetTransactionFactory factory = new CdcTargetTransactionFactory(
                new CdcRuntimeStats(), 0, targets, mock(RedisProjectionWriter.class));

        assertThat(factory.open(redis, ctx))
                .isInstanceOf(RedisCdcTargetTransaction.class);
    }

    @Test
    void aDroppedHashColumnAcrossTransactionsParksTheProject() throws Exception {
        // A DDL cannot run inside one CDC transaction, so the layout history has to live in the
        // factory (one per run) for this check to see anything. A column that vanished after a
        // DDL leaves its old HASH field in Redis forever; parking names the rebuild remedy.
        RedisTargetService targets = mock(RedisTargetService.class);
        RedisTarget target = new RedisTarget();
        target.setId(9L);
        target.setHost("127.0.0.1");
        target.setPort(6379);
        when(targets.find(9L)).thenReturn(Optional.of(target));

        Project redis = project(SyncTargetKind.REDIS);
        redis.setTargetRedisId(9L);
        SyncConfig config = new SyncConfig();
        RedisProjectionConfig projection = RedisProjectionConfig.defaultFor(1L);
        projection.setValueFormat(RedisValueFormat.HASH);
        config.setRedisProjection(projection);
        SyncContext ctx = SyncContext.builder().project(redis).config(config).build();

        CdcTargetTransactionFactory factory = new CdcTargetTransactionFactory(
                new CdcRuntimeStats(), 0, targets, mock(RedisProjectionWriter.class));

        CdcTargetTransaction first = factory.open(redis, ctx);
        first.applyUpserts(binding("id", "name", "extra"),
                List.of(row("id", 1L, "name", "a", "extra", "x")));
        first.commit();
        first.close();

        CdcTargetTransaction second = factory.open(redis, ctx);
        assertThatThrownBy(() -> second.applyUpserts(binding("id", "name"),
                List.of(row("id", 1L, "name", "a"))))
                .isInstanceOf(RedisLayoutDriftException.class);
        second.close();
    }

    @Test
    void aJdbcOnlyFactoryRefusesAredisProject() {
        CdcTargetTransactionFactory factory =
                new CdcTargetTransactionFactory(new CdcRuntimeStats(), 0);

        assertThatThrownBy(() -> factory.open(project(SyncTargetKind.REDIS),
                context(mock(Connection.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis");
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
