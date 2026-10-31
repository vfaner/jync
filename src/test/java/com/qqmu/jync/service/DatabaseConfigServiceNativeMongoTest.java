package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

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
 * The native MongoDB connection is not a JDBC connection: it must save without a jar, must
 * never be a TARGET, and its preview must speak Mongo URIs with the password masked.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseConfigServiceNativeMongoTest {

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

    private static DatabaseConfig nativeMongo(ConnectionRole role) {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("mongo-native");
        c.setType(DatabaseType.MONGODB_NATIVE);
        c.setRole(role);
        c.setHost("localhost");
        c.setDatabaseName("shop");
        return c;
    }

    @Test
    void aNativeSourceSavesWithoutAJarAndDefaultsThePort() {
        DatabaseConfig c = nativeMongo(ConnectionRole.SOURCE);
        c.setCustomDriver("com.example.Ignored");
        c.setCustomJarPath("/opt/ignored.jar");
        when(repository.findByName(any())).thenReturn(Optional.empty());
        when(cryptoUtil.encrypt(any())).thenReturn("enc");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DatabaseConfig saved = service.save(c, "secret");

        assertThat(saved.getPort()).isEqualTo(27017);
        assertThat(saved.getCustomDriver()).isNull();
        assertThat(saved.getCustomJarPath()).isNull();
        verify(repository).save(any());
    }

    @Test
    void aNativeConnectionsCannotBeATarget() {
        DatabaseConfig c = nativeMongo(ConnectionRole.TARGET);

        assertThatThrownBy(() -> service.save(c, "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.sourceOnly");
        verify(repository, never()).save(any());
    }

    @Test
    void aCustomUriWithoutADatabaseIsRejected() {
        DatabaseConfig c = nativeMongo(ConnectionRole.SOURCE);
        c.setDatabaseName(null);
        c.setCustomUrl("mongodb://mongo.internal:27018");

        assertThatThrownBy(() -> service.save(c, "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.database.required");
        verify(repository, never()).save(any());
    }

    @Test
    void aJdbcMongoUrlIsRejected() {
        DatabaseConfig c = nativeMongo(ConnectionRole.SOURCE);
        c.setCustomUrl("jdbc:mongodb://mongo.internal/shop");

        assertThatThrownBy(() -> service.save(c, "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.invalid");
    }

    @Test
    void thePreviewIsAMaskedMongoUri() {
        DatabaseConfig c = nativeMongo(ConnectionRole.SOURCE);
        c.setUsername("alice");
        c.setPassword("encrypted");

        assertThat(service.previewUrl(c))
                .isEqualTo("mongodb://alice:****@localhost:27017/shop");
    }
}
