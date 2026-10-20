package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.GenericSqlDialect;

/**
 * The per-project setting is layered over the global one: a project may run its own full load
 * sequentially while the rest of the install parallelizes, and a table whose key cannot be
 * range-split never parallelizes whatever the configuration says.
 */
class DataSyncServiceParallelismResolutionTest {

    private final SyncProperties properties = new SyncProperties();
    private final DataSyncService service =
            new DataSyncService(properties, mock(ChunkCheckpointRepository.class));

    @Test
    void projectValueOverridesTheGlobalSetting() {
        properties.setFullLoadParallelism(4);

        assertThat(service.resolveParallelism(FullLoadRoute.KEYSET_NUMERIC, intKeyTable(),
                ctx(2))).isEqualTo(2);
    }

    @Test
    void unsetProjectValueFallsBackToTheGlobalSetting() {
        properties.setFullLoadParallelism(3);

        assertThat(service.resolveParallelism(FullLoadRoute.KEYSET_NUMERIC, intKeyTable(),
                ctx(0))).isEqualTo(3);
    }

    @Test
    void parallelismIsStillCappedAtEight() {
        properties.setFullLoadParallelism(1);

        assertThat(service.resolveParallelism(FullLoadRoute.KEYSET_NUMERIC, intKeyTable(),
                ctx(64))).isEqualTo(8);
    }

    @Test
    void aKeyThatCannotBeRangeSplitStaysSequential() {
        properties.setFullLoadParallelism(4);

        TableMeta varcharKey = new TableMeta();
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.VARCHAR);
        varcharKey.setName("ITEMS");
        varcharKey.setPrimaryKeys(List.of("ID"));
        varcharKey.setColumns(List.of(id));

        assertThat(service.resolveParallelism(FullLoadRoute.KEYSET_NUMERIC, varcharKey,
                ctx(4))).isEqualTo(1);
    }

    @Test
    void aRouteWithoutKeysetChunkingStaysSequential() {
        properties.setFullLoadParallelism(4);

        assertThat(service.resolveParallelism(FullLoadRoute.STREAMING_FALLBACK, intKeyTable(),
                ctx(4))).isEqualTo(1);
    }

    @Test
    void aContextWithoutConnectionProviderStaysSequential() {
        properties.setFullLoadParallelism(4);
        SyncContext noProvider = SyncContext.builder()
                .config(new SyncConfig())
                .sourceConfig(new DatabaseConfig())
                .targetConfig(new DatabaseConfig())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .fullLoadParallelism(4)
                .build();

        assertThat(service.resolveParallelism(FullLoadRoute.KEYSET_NUMERIC, intKeyTable(),
                noProvider)).isEqualTo(1);
    }

    private static SyncContext ctx(int projectParallelism) {
        DatabaseConfig source = new DatabaseConfig();
        source.setId(1L);
        DatabaseConfig target = new DatabaseConfig();
        target.setId(2L);
        SyncContext.SyncContextBuilder builder = SyncContext.builder()
                .config(new SyncConfig())
                .sourceConfig(source)
                .targetConfig(target)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .connectionProvider(cfg -> null);
        return projectParallelism > 0
                ? builder.fullLoadParallelism(projectParallelism).build()
                : builder.build();
    }

    private static TableMeta intKeyTable() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id));
        return table;
    }
}
