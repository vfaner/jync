package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.ConnectionTestService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Saving a connection must not silently put a port back into a field the user cleared.
 * For SQL Server a blank port is deliberate: it is how a named instance is addressed,
 * and backfilling 1433 is exactly what made those connections time out.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseConfigServicePortDefaultTest {

    @Mock private DatabaseConfigRepository repository;
    @Mock private ProjectRepository projectRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private ConnectionTestService connectionTestService;
    @Mock private CryptoUtil cryptoUtil;
    @Mock private AdminAuditService auditService;

    private DatabaseConfigService service;

    @BeforeEach
    void setUp() {
        service = new DatabaseConfigService(repository, projectRepository,
                dataSourceManager, connectionTestService, cryptoUtil, auditService);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private DatabaseConfig config(DatabaseType type, Integer port) {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("conn-" + type.name());
        c.setType(type);
        c.setRole(ConnectionRole.SOURCE);
        c.setHost("localhost");
        c.setPort(port);
        return c;
    }

    @Test
    void sqlServerBlankPortIsSavedBlank() {
        DatabaseConfig saved = service.save(config(DatabaseType.SQLSERVER, null), null);

        assertThat(saved.getPort()).isNull();
    }

    @Test
    void otherPresetsStillGetTheirDefaultPortWhenBlank() {
        assertThat(service.save(config(DatabaseType.MYSQL, null), null).getPort())
                .isEqualTo(3306);
        assertThat(service.save(config(DatabaseType.ORACLE, null), null).getPort())
                .isEqualTo(1521);
    }
}
