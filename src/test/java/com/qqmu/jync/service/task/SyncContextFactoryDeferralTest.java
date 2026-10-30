package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.PoolContentionException;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;

/**
 * The schema lookup opens a connection before the cycle's own two, so it is the first place a
 * task can meet a pool another task has fully checked out. It must surface that as a
 * deferral — the runner then records nothing and retries next fire — while an unreachable
 * database keeps its configuration error.
 */
class SyncContextFactoryDeferralTest {

    private DataSourceManager dataSourceManager;
    private SyncContextFactory factory;
    private Project project;
    private DatabaseConfig source;
    private DatabaseConfig target;

    @BeforeEach
    void setUp() {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        dataSourceManager = mock(DataSourceManager.class);
        MetadataReaderFactory readerFactory = mock(MetadataReaderFactory.class);
        SqlDialectFactory dialectFactory = mock(SqlDialectFactory.class);

        source = db(1L);
        target = db(2L);
        project = new Project();
        project.setId(9L);
        project.setName("orders");
        project.setSourceDbId(1L);
        project.setTargetDbId(2L);
        when(repository.findById(1L)).thenReturn(Optional.of(source));
        when(repository.findById(2L)).thenReturn(Optional.of(target));

        MetadataReader reader = mock(MetadataReader.class);
        when(readerFactory.forType(any(), any())).thenReturn(reader);
        when(dialectFactory.forType(any(), any(), any())).thenReturn(mock(SqlDialect.class));

        factory = new SyncContextFactory(repository, dataSourceManager, readerFactory,
                dialectFactory, null, new SyncProperties(), null,
                new PoolActivity(new SyncProperties()));
    }

    @Test
    void aBusyPoolDuringSchemaResolutionIsADeferral() throws SQLException {
        when(dataSourceManager.getConnectionOrDefer(eq(source)))
                .thenThrow(new PoolContentionException("pool busy"));

        assertThatThrownBy(() -> factory.build(project, "owner", () -> true))
                .isInstanceOf(PoolContentionException.class);
    }

    @Test
    void anUnreachableDatabaseStaysAConfigurationError() throws SQLException {
        when(dataSourceManager.getConnectionOrDefer(eq(source)))
                .thenThrow(new SQLTransientConnectionException("Connection refused"));

        assertThatThrownBy(() -> factory.build(project, "owner", () -> true))
                .isInstanceOf(IllegalStateException.class);
    }

    private static DatabaseConfig db(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName("db" + id);
        config.setType(DatabaseType.MYSQL);
        return config;
    }
}
