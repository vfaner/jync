package com.qqmu.jync.service.migration.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoIterable;
import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;

@ExtendWith(MockitoExtension.class)
class MongoConnectionAdapterTest {

    @Mock private MongoMigrationClientFactory factory;
    @Mock private MongoClient client;
    @Mock private MongoDatabase database;
    @Mock private MongoCollection<Document> collection;
    @Mock private MongoIterable<String> collectionNames;

    private MongoConnectionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MongoConnectionAdapter(factory);
    }

    private MigrationConnectionForm form() {
        MigrationConnectionForm form = new MigrationConnectionForm();
        form.setKind(MigrationConnectionKind.MONGODB);
        form.setBaseUrl("mongodb://mongo.internal:27017");
        form.setDatabaseName("orders");
        form.setAuthType(MigrationConnectionAuthType.BASIC);
        form.setUsername("app");
        return form;
    }

    private MigrationConnection connection() {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(3L);
        connection.setKind(MigrationConnectionKind.MONGODB);
        connection.setBaseUrl("mongodb://mongo.internal:27017");
        connection.setDatabaseName("orders");
        connection.setAuthType(MigrationConnectionAuthType.BASIC);
        connection.setUsername("app");
        connection.setPasswordEnc("enc:pw");
        connection.setConnectTimeoutMs(5000);
        connection.setRequestTimeoutMs(60000);
        return connection;
    }

    private void stubProbe() {
        when(factory.open(any(MigrationConnection.class))).thenReturn(client);
        when(factory.database(any(MigrationConnection.class))).thenReturn("orders");
        when(client.getDatabase("orders")).thenReturn(database);
        when(database.getName()).thenReturn("orders");
        when(database.runCommand(any(Document.class))).thenAnswer(invocation -> {
            Document command = invocation.getArgument(0);
            return command.containsKey("buildInfo")
                    ? new Document("version", "6.0.5")
                    : new Document("ok", 1);
        });
    }

    @Test
    void itOwnsOnlyTheMongoKind() {
        assertThat(adapter.kinds()).containsExactly(MigrationConnectionKind.MONGODB);
    }

    @Test
    void aMongodbUriIsAccepted() {
        MigrationConnectionEndpoint endpoint = adapter.normalize(form());

        assertThat(endpoint.baseUrl()).isEqualTo("mongodb://mongo.internal:27017");
        assertThat(endpoint.databaseName()).isEqualTo("orders");
        assertThat(endpoint.schemaName()).isNull();
    }

    @Test
    void anSrvUriIsAccepted() {
        MigrationConnectionForm form = form();
        form.setBaseUrl("mongodb+srv://cluster.example.net");
        form.setDatabaseName("orders");

        assertThat(adapter.normalize(form).baseUrl())
                .isEqualTo("mongodb+srv://cluster.example.net");
    }

    @Test
    void anSrvUriWithAnExplicitPortIsRejected() {
        MigrationConnectionForm form = form();
        form.setBaseUrl("mongodb+srv://cluster.example.net:27017");

        assertThatThrownBy(() -> adapter.normalize(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.invalid");
    }

    @Test
    void aNonMongoUriIsRejected() {
        MigrationConnectionForm form = form();
        form.setBaseUrl("http://127.0.0.1:9200");

        assertThatThrownBy(() -> adapter.normalize(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.invalid");
    }

    @Test
    void aPasswordInsideTheUriIsRejected() {
        MigrationConnectionForm form = form();
        form.setBaseUrl("mongodb://app:plain@mongo.internal:27017");

        assertThatThrownBy(() -> adapter.normalize(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.passwordInUrl");
    }

    @Test
    void theUriPathAndTheDatabaseFieldMustAgree() {
        MigrationConnectionForm form = form();
        form.setBaseUrl("mongodb://mongo.internal:27017/analytics");
        form.setDatabaseName("orders");

        assertThatThrownBy(() -> adapter.normalize(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.mongo.database.mismatch");
    }

    @Test
    void basicAuthNeedsAUsernameAndAPassword() {
        MigrationConnectionForm noUser = form();
        noUser.setUsername(" ");
        assertThatThrownBy(() -> adapter.validateAuth(noUser, "pw", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.username.required");

        MigrationConnectionForm noPassword = form();
        assertThatThrownBy(() -> adapter.validateAuth(noPassword, "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.password.required");
    }

    @Test
    void aStoredPasswordSatisfiesTheRequirementWhenEditing() {
        MigrationConnectionForm form = form();

        adapter.validateAuth(form, "", null, connection());
    }

    @Test
    void anApiKeyIsNotSupported() {
        MigrationConnectionForm form = form();
        form.setAuthType(MigrationConnectionAuthType.API_KEY);

        assertThatThrownBy(() -> adapter.validateAuth(form, null, "id:key", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.mongo.auth.unsupported");
    }

    @Test
    void probingReportsTheServerVersionAndDatabase() {
        stubProbe();

        MigrationConnectionTestResult result = adapter.test(connection());

        assertThat(result.success()).isTrue();
        assertThat(result.product()).isEqualTo("MongoDB");
        assertThat(result.version()).isEqualTo("6.0.5");
        assertThat(result.clusterName()).isEqualTo("orders");
    }

    @Test
    void aFailedProbeReportsTheMessage() {
        when(factory.open(any(MigrationConnection.class)))
                .thenThrow(new IllegalStateException("timed out"));

        MigrationConnectionTestResult result = adapter.test(connection());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("timed out");
    }

    @Test
    void discoverySkipsSystemCollectionsUnlessAskedAndHonoursThePattern() {
        when(factory.open(any(MigrationConnection.class))).thenReturn(client);
        when(factory.database(any(MigrationConnection.class))).thenReturn("orders");
        when(client.getDatabase("orders")).thenReturn(database);
        when(database.listCollectionNames()).thenReturn(collectionNames);
        when(collectionNames.into(any())).thenReturn(
                List.of("orders", "users", "system.views"));
        when(database.getCollection(any(String.class))).thenReturn(collection);
        when(collection.estimatedDocumentCount()).thenReturn(12L);

        List<MigrationDiscoveryItem> plain = adapter.list(connection(), "orders,users");
        assertThat(plain).extracting(MigrationDiscoveryItem::name)
                .containsExactly("orders", "users");
        assertThat(plain.get(0).docsCount()).isEqualTo(12L);

        List<MigrationDiscoveryItem> withSystem = adapter.list(connection(), "*,system.*");
        assertThat(withSystem).extracting(MigrationDiscoveryItem::name)
                .containsExactly("orders", "users", "system.views");
    }
}
