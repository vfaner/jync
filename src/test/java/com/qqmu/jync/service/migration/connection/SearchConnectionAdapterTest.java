package com.qqmu.jync.service.migration.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.service.migration.es.SearchHttpClient;
import com.qqmu.jync.service.migration.es.SearchHttpClientFactory;

@ExtendWith(MockitoExtension.class)
class SearchConnectionAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private SearchHttpClientFactory clientFactory;
    @Mock private SearchHttpClient client;

    private SearchConnectionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SearchConnectionAdapter(clientFactory);
    }

    private MigrationConnectionForm form() {
        MigrationConnectionForm form = new MigrationConnectionForm();
        form.setKind(MigrationConnectionKind.ELASTICSEARCH);
        form.setBaseUrl("http://127.0.0.1:9200/");
        form.setAuthType(MigrationConnectionAuthType.BASIC);
        form.setUsername("elastic");
        // Fields only MongoDB/KingbaseES use must never leak into a search row.
        form.setDatabaseName("stray");
        form.setSchemaName("stray");
        form.setExtraParams("stray");
        return form;
    }

    private MigrationConnection connection() {
        MigrationConnection connection = new MigrationConnection();
        connection.setKind(MigrationConnectionKind.ELASTICSEARCH);
        connection.setBaseUrl("http://127.0.0.1:9200");
        connection.setAuthType(MigrationConnectionAuthType.BASIC);
        connection.setUsername("elastic");
        connection.setConnectTimeoutMs(5000);
        connection.setRequestTimeoutMs(60000);
        return connection;
    }

    @Test
    void itOwnsEverySearchKind() {
        assertThat(adapter.kinds()).containsExactlyInAnyOrder(
                MigrationConnectionKind.ELASTICSEARCH,
                MigrationConnectionKind.OPENSEARCH,
                MigrationConnectionKind.EASYSEARCH);
    }

    @Test
    void normalisingStripsTheTrailingSlashAndClearsUnrelatedFields() {
        MigrationConnectionEndpoint endpoint = adapter.normalize(form());

        assertThat(endpoint.baseUrl()).isEqualTo("http://127.0.0.1:9200");
        assertThat(endpoint.databaseName()).isNull();
        assertThat(endpoint.schemaName()).isNull();
        assertThat(endpoint.extraParams()).isNull();
    }

    @Test
    void aNonHttpSchemeIsRejectedWithAKey() {
        MigrationConnectionForm form = form();
        form.setBaseUrl("ftp://host:21");

        assertThatThrownBy(() -> adapter.normalize(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.url.invalid");
    }

    @Test
    void basicAuthWithoutAUsernameIsRejected() {
        MigrationConnectionForm form = form();
        form.setUsername(" ");

        assertThatThrownBy(() -> adapter.validateAuth(form, "x", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.username.required");
    }

    @Test
    void anApiKeyIsRequiredWhenNoneIsStored() {
        MigrationConnectionForm form = form();
        form.setAuthType(MigrationConnectionAuthType.API_KEY);

        assertThatThrownBy(() -> adapter.validateAuth(form, null, " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.apiKey.required");
    }

    @Test
    void probingReportsProductVersionAndCluster() throws Exception {
        MigrationConnection connection = connection();
        when(clientFactory.create(connection)).thenReturn(client);
        JsonNode root = JSON.readTree("{\"cluster_name\":\"prod\","
                + "\"version\":{\"number\":\"7.17.22\",\"distribution\":\"elasticsearch\"}}");
        when(client.get("/")).thenReturn(root);

        MigrationConnectionTestResult result = adapter.test(connection);

        assertThat(result.success()).isTrue();
        assertThat(result.product()).isEqualTo("elasticsearch");
        assertThat(result.version()).isEqualTo("7.17.22");
        assertThat(result.clusterName()).isEqualTo("prod");
    }

    @Test
    void probingAFailedConnectionReturnsTheMessageInsteadOfThrowing() {
        MigrationConnection connection = connection();
        when(clientFactory.create(connection)).thenReturn(client);
        when(client.get("/")).thenThrow(new IllegalStateException("connection refused"));

        MigrationConnectionTestResult result = adapter.test(connection);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("connection refused");
        assertThat(result.product()).isNull();
    }

    @Test
    void discoveryFiltersSystemIndicesUnlessThePatternAsksForThem() throws Exception {
        MigrationConnection connection = connection();
        when(clientFactory.create(connection)).thenReturn(client);
        JsonNode cat = JSON.readTree("["
                + "{\"index\":\"orders-2026\",\"docs.count\":\"12\",\"store.size\":\"1kb\","
                + "\"status\":\"open\",\"health\":\"green\"},"
                + "{\"index\":\"users\",\"docs.count\":\"3\",\"store.size\":\"1kb\","
                + "\"status\":\"open\",\"health\":\"green\"},"
                + "{\"index\":\".security\",\"docs.count\":\"9\",\"store.size\":\"1kb\","
                + "\"status\":\"open\",\"health\":\"green\"}]");
        when(client.get(startsWith("/_cat/indices"))).thenReturn(cat);

        List<MigrationDiscoveryItem> plain = adapter.list(connection, "orders-*");
        assertThat(plain).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("orders-2026");
            assertThat(item.docsCount()).isEqualTo(12L);
        });

        List<MigrationDiscoveryItem> withSystem = adapter.list(connection, "*,.*");
        assertThat(withSystem).extracting(MigrationDiscoveryItem::name)
                .containsExactly("orders-2026", "users", ".security");
    }
}
