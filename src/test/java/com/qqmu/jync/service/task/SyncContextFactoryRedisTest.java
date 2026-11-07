package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.OracleVersionResolver;
import com.qqmu.jync.service.connection.ServerVersionResolver;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.projection.redis.RedisValueFormat;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * A Redis project has no target database, so the ordinary {@code build} cannot serve it: it
 * refuses a project without a target id. The Redis variant resolves the source side only.
 */
class SyncContextFactoryRedisTest {

    private DatabaseConfigRepository repository;
    private DataSourceManager dataSourceManager;
    private MetadataReaderFactory readerFactory;
    private SqlDialectFactory dialectFactory;
    private ServerVersionResolver versionResolver;
    private SyncProperties properties;
    private SyncContextFactory factory;

    @BeforeEach
    void setUp() {
        repository = mock(DatabaseConfigRepository.class);
        dataSourceManager = mock(DataSourceManager.class);
        readerFactory = mock(MetadataReaderFactory.class);
        dialectFactory = mock(SqlDialectFactory.class);
        properties = new SyncProperties();
        versionResolver = mock(ServerVersionResolver.class);
        factory = new SyncContextFactory(repository, dataSourceManager, readerFactory,
                dialectFactory, mock(OracleVersionResolver.class), versionResolver, properties,
                new ObjectMapper(), new PoolActivity(properties));

        MetadataReader reader = mock(MetadataReader.class);
        when(readerFactory.forType(any(), any())).thenReturn(reader);
        when(dialectFactory.forType(any(), isNull(), any())).thenReturn(new GenericSqlDialect());
        when(versionResolver.majorVersion(any())).thenReturn(null);
        DatabaseConfig source = new DatabaseConfig();
        source.setId(5L);
        source.setName("src");
        source.setType(DatabaseType.MYSQL);
        source.setSchemaName("app");
        when(repository.findById(5L)).thenReturn(Optional.of(source));
    }

    private static Project redisProject() {
        Project project = new Project();
        project.setId(1L);
        project.setName("orders-cache");
        project.setSourceDbId(5L);
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(9L);
        project.setSyncConfig("{\"captureStrategy\":\"BINLOG_CDC\","
                + "\"redisProjection\":{\"keyPrefix\":\"jync:1:\"}}");
        return project;
    }

    @Test
    void buildForRedisLeavesTargetSideNull() {
        BooleanSupplier renew = () -> true;
        BooleanSupplier stop = () -> false;

        SyncContext ctx = factory.buildForRedis(redisProject(), "host:1", renew, stop);

        assertThat(ctx.getSourceType()).isEqualTo(DatabaseType.MYSQL);
        assertThat(ctx.getSourceSchema()).isEqualTo("app");
        assertThat(ctx.getSourceReader()).isNotNull();
        assertThat(ctx.getSourceDialect()).isInstanceOf(GenericSqlDialect.class);
        assertThat(ctx.getTargetConfig()).isNull();
        assertThat(ctx.getTargetType()).isNull();
        assertThat(ctx.getTargetDialect()).isNull();
        assertThat(ctx.getConnectionProvider()).isNull();
        assertThat(ctx.getConfig().getRedisProjection().getKeyPrefix()).isEqualTo("jync:1:");
        assertThat(ctx.getBatchSize()).isEqualTo(properties.getBatchSize());
        assertThat(ctx.getLockOwner()).isEqualTo("host:1");
        assertThat(ctx.getLockRenewer()).isSameAs(renew);
        assertThat(ctx.getStopRequested()).isSameAs(stop);
    }

    @Test
    void buildForRedisDoesNotRequireATargetDatabase() {
        Project project = redisProject();
        project.setTargetDbId(null);

        assertThat(factory.buildForRedis(project, "host:1", () -> true, null).getTargetConfig())
                .isNull();
    }

    @Test
    void buildForRedisFillsTheDefaultContractWhenTheProjectHasNone() {
        // A project switched to a cache target before its settings page was ever saved carries
        // no redisProjection node. The consumer and the rebuild button used to die with the
        // internal English "Redis projection settings are missing"; the default prefix is what
        // the settings page would have stored.
        Project project = redisProject();
        project.setSyncConfig("{\"captureStrategy\":\"BINLOG_CDC\"}");

        SyncContext ctx = factory.buildForRedis(project, "host:1", () -> true, null);

        assertThat(ctx.getConfig().getRedisProjection()).isNotNull();
        assertThat(ctx.getConfig().getRedisProjection().getKeyPrefix()).isEqualTo("jync:1:");
        assertThat(ctx.getConfig().getRedisProjection().getValueFormat())
                .isEqualTo(RedisValueFormat.JSON);
    }

    @Test
    void buildForRedisFailsCleanlyWithoutASource() {
        Project project = redisProject();
        project.setSourceDbId(null);

        assertThatThrownBy(() -> factory.buildForRedis(project, "host:1", () -> true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source");
    }
}
