package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * "Test" on an edit form posts the transient settings. A password field left blank means
 * "keep the stored one" — the same contract as save, and for an Oracle CDB source that
 * includes the mining password the LogMiner readiness probe inspects to decide whether a
 * mining account is configured.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseConfigServiceTransientTest {

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
    }

    private DatabaseConfig stored() {
        DatabaseConfig c = new DatabaseConfig();
        c.setId(1L);
        c.setName("ora-cdb");
        c.setType(DatabaseType.ORACLE);
        c.setRole(ConnectionRole.SOURCE);
        c.setHost("db.example");
        c.setPort(1521);
        c.setPassword("enc-pw");
        c.setMinerUsername("C##MINER");
        c.setMinerPassword("enc-miner");
        return c;
    }

    /** What the form submits: no secrets (the browser never round-trips them). */
    private DatabaseConfig form() {
        DatabaseConfig c = new DatabaseConfig();
        c.setId(1L);
        c.setName("ora-cdb");
        c.setType(DatabaseType.ORACLE);
        c.setRole(ConnectionRole.SOURCE);
        c.setHost("db.example");
        c.setPort(1521);
        c.setMinerUsername("C##MINER");
        return c;
    }

    private DatabaseConfig tested(DatabaseConfig config, String rawPassword,
                                  String rawMinerPassword) {
        service.testTransient(config, rawPassword, rawMinerPassword);
        ArgumentCaptor<DatabaseConfig> captor = ArgumentCaptor.forClass(DatabaseConfig.class);
        verify(connectionTestService).test(captor.capture());
        return captor.getValue();
    }

    @Test
    void blankFieldsOnAnEditKeepTheStoredSecrets() {
        when(repository.findById(1L)).thenReturn(Optional.of(stored()));

        DatabaseConfig tested = tested(form(), "", "");

        assertThat(tested.getPassword()).isEqualTo("enc-pw");
        // The probe decides "miner configured" from this field; losing it made a CDB source
        // with a saved mining account report a red "configure me" check on every test.
        assertThat(tested.getMinerPassword()).isEqualTo("enc-miner");
    }

    @Test
    void typedSecretsReplaceTheStoredOnes() {
        when(cryptoUtil.encrypt("fresh-pw")).thenReturn("enc-pw-2");
        when(cryptoUtil.encrypt("fresh-miner")).thenReturn("enc-miner-2");

        DatabaseConfig tested = tested(form(), "fresh-pw", "fresh-miner");

        assertThat(tested.getPassword()).isEqualTo("enc-pw-2");
        assertThat(tested.getMinerPassword()).isEqualTo("enc-miner-2");
    }
}
