package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.OracleVersionResolver;
import com.qqmu.jync.service.connection.ServerVersionResolver;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * The context's dialects must be configured with the version of the server they will talk to,
 * on both sides: a MySQL 5.7 source cannot sample keys with window functions, and a 5.7 target
 * rejects expression defaults in the DDL the sync itself writes.
 */
class SyncContextFactoryVersionTest {

    private DatabaseConfigRepository repository;
    private ServerVersionResolver versionResolver;
    private OracleVersionResolver oracleVersionResolver;
    private SyncContextFactory factory;
    private DatabaseConfig source;
    private DatabaseConfig target;

    @BeforeEach
    void setUp() {
        repository = mock(DatabaseConfigRepository.class);
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        MetadataReaderFactory readerFactory = mock(MetadataReaderFactory.class);
        versionResolver = mock(ServerVersionResolver.class);
        oracleVersionResolver = mock(OracleVersionResolver.class);
        SyncProperties properties = new SyncProperties();

        source = db(1L, "src", DatabaseType.MYSQL);
        target = db(2L, "dst", DatabaseType.MYSQL);
        when(repository.findById(1L)).thenReturn(Optional.of(source));
        when(repository.findById(2L)).thenReturn(Optional.of(target));
        when(readerFactory.forType(any(), any())).thenReturn(mock(MetadataReader.class));

        factory = new SyncContextFactory(repository, dataSourceManager, readerFactory,
                new SqlDialectFactory(List.of(new MySqlDialect(), new OracleDialect()),
                        new GenericSqlDialect()),
                oracleVersionResolver, versionResolver, properties,
                new ObjectMapper(), new PoolActivity(properties));
    }

    @Test
    void aMysql57SourceLosesWindowFunctions() {
        when(versionResolver.majorVersion(eq(source))).thenReturn(5);

        SyncContext ctx = factory.build(project(), "host:1", () -> true);

        assertThat(ctx.getSourceDialect().supportsWindowFunctions()).isFalse();
        verify(versionResolver).majorVersion(source);
    }

    @Test
    void aMysql80SourceKeepsWindowFunctions() {
        when(versionResolver.majorVersion(eq(source))).thenReturn(8);

        SyncContext ctx = factory.build(project(), "host:1", () -> true);

        assertThat(ctx.getSourceDialect().supportsWindowFunctions()).isTrue();
    }

    @Test
    void theTargetSideGetsItsOwnVersion() {
        // The two endpoints are separate servers: the source's 8.0 says nothing about the
        // target's DDL grammar.
        when(versionResolver.majorVersion(eq(source))).thenReturn(8);
        when(versionResolver.majorVersion(eq(target))).thenReturn(5);

        SyncContext ctx = factory.build(project(), "host:1", () -> true);

        assertThat(((MySqlDialect) ctx.getTargetDialect()).supportsWindowFunctions()).isFalse();
    }

    @Test
    void anUnresolvedVersionKeepsTheAttemptThenFallBackBehavior() {
        when(versionResolver.majorVersion(any())).thenReturn(null);

        SyncContext ctx = factory.build(project(), "host:1", () -> true);

        assertThat(ctx.getSourceDialect().supportsWindowFunctions()).isTrue();
        assertThat(ctx.getTargetDialect().supportsWindowFunctions()).isTrue();
    }

    @Test
    void anOracleSourceIsNotGrammarProbed() {
        // The Oracle probe opens a connection and runs three round-trips; before this feature
        // it ran for Oracle targets only. A source's version decides key sampling, which
        // Oracle does not need, so it must stay off the source path.
        source.setType(DatabaseType.ORACLE);
        when(versionResolver.majorVersion(eq(source))).thenReturn(null);

        factory.build(project(), "host:1", () -> true);

        verify(oracleVersionResolver, never()).resolveDialectVersion(any());
    }

    @Test
    void buildSourceOnlyAlsoCarriesTheSourceVersion() {
        // The Redis baseline full load reads the source through this path.
        when(versionResolver.majorVersion(eq(source))).thenReturn(5);

        SyncContext ctx = factory.buildSourceOnly(project(), factory.parseConfig(project()));

        assertThat(ctx.getSourceDialect().supportsWindowFunctions()).isFalse();
    }

    private static Project project() {
        Project project = new Project();
        project.setId(9L);
        project.setName("orders");
        project.setSourceDbId(1L);
        project.setTargetDbId(2L);
        return project;
    }

    private static DatabaseConfig db(Long id, String name, DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setType(type);
        // A configured schema keeps build() from opening a connection just to resolve one.
        config.setSchemaName("app");
        return config;
    }
}
