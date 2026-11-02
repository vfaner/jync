package com.qqmu.jync.service.migration.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.migration.MigrationConnectionService;
import com.qqmu.jync.util.CryptoUtil;

@ExtendWith(MockitoExtension.class)
class MigrationConnectionServiceMongoTest {

    @Mock private MigrationConnectionRepository connections;
    @Mock private MigrationTaskRepository tasks;
    @Mock private CryptoUtil cryptoUtil;
    @Mock private AdminAuditService auditService;
    @Mock private MongoMigrationClientFactory factory;
    @Mock private MongoClient client;
    @Mock private MongoDatabase database;

    private MigrationConnectionService service;

    @BeforeEach
    void setUp() {
        service = new MigrationConnectionService(connections, tasks, cryptoUtil, auditService,
                new MigrationConnectionAdapterRegistry(
                        List.of(new MongoConnectionAdapter(factory))));
    }

    private MigrationConnectionForm form() {
        MigrationConnectionForm form = new MigrationConnectionForm();
        form.setName("orders-mongo");
        form.setKind(MigrationConnectionKind.MONGODB);
        form.setBaseUrl("mongodb://mongo.internal:27017");
        form.setDatabaseName("orders");
        form.setAuthType(MigrationConnectionAuthType.BASIC);
        form.setUsername("app");
        form.setExtraParams("authSource=admin");
        form.setConnectTimeoutMs(5000);
        form.setRequestTimeoutMs(60000);
        return form;
    }

    private MigrationConnection stored() {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(3L);
        connection.setName("orders-mongo");
        connection.setKind(MigrationConnectionKind.MONGODB);
        connection.setBaseUrl("mongodb://mongo.internal:27017");
        connection.setDatabaseName("orders");
        connection.setAuthType(MigrationConnectionAuthType.BASIC);
        connection.setUsername("app");
        connection.setPasswordEnc("enc:old");
        connection.setConnectTimeoutMs(5000);
        connection.setRequestTimeoutMs(60000);
        return connection;
    }

    @Test
    void savingAMongoConnectionStoresTheDatabaseAndEncryptsThePassword() {
        when(connections.findByName("orders-mongo")).thenReturn(Optional.empty());
        when(cryptoUtil.encrypt("s3cret")).thenReturn("enc:new");
        when(connections.save(any(MigrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.save(form(), "s3cret", null);

        ArgumentCaptor<MigrationConnection> captor =
                ArgumentCaptor.forClass(MigrationConnection.class);
        verify(connections).save(captor.capture());
        MigrationConnection saved = captor.getValue();
        assertThat(saved.getKind()).isEqualTo(MigrationConnectionKind.MONGODB);
        assertThat(saved.getBaseUrl()).isEqualTo("mongodb://mongo.internal:27017");
        assertThat(saved.getDatabaseName()).isEqualTo("orders");
        assertThat(saved.getExtraParams()).isEqualTo("authSource=admin");
        assertThat(saved.getSchemaName()).isNull();
        assertThat(saved.getPasswordEnc()).isEqualTo("enc:new");
    }

    @Test
    void editingWithABlankPasswordKeepsTheStoredOne() {
        MigrationConnection existing = stored();
        MigrationConnectionForm form = form();
        form.setId(3L);
        when(connections.findByName("orders-mongo")).thenReturn(Optional.of(existing));
        when(connections.findById(3L)).thenReturn(Optional.of(existing));
        when(connections.save(any(MigrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.save(form, "", null);

        assertThat(existing.getPasswordEnc()).isEqualTo("enc:old");
    }

    @Test
    void aTransientTestDecryptsTheStoredPasswordBeforeConnecting() {
        MigrationConnection existing = stored();
        MigrationConnectionForm form = form();
        form.setId(3L);
        when(connections.findById(3L)).thenReturn(Optional.of(existing));
        when(cryptoUtil.decrypt("enc:old")).thenReturn("old-secret");
        when(factory.open(any(MigrationConnection.class), eq("old-secret"))).thenReturn(client);
        when(factory.database(any(MigrationConnection.class))).thenReturn("orders");
        when(client.getDatabase("orders")).thenReturn(database);
        when(database.getName()).thenReturn("orders");
        when(database.runCommand(any(Document.class)))
                .thenReturn(new Document("version", "6.0.5"));

        MigrationConnectionTestResult result = service.testTransient(form, "", null);

        assertThat(result.success()).isTrue();
        verify(factory).open(any(MigrationConnection.class), eq("old-secret"));
    }
}
