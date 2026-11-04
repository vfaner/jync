package com.qqmu.jync.service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.audit.AuditDetailRenderer;
import com.qqmu.jync.service.migration.connection.MigrationConnectionAdapterRegistry;
import com.qqmu.jync.service.migration.connection.MigrationConnectionTestResult;
import com.qqmu.jync.service.migration.connection.MigrationDiscoveryItem;
import com.qqmu.jync.service.migration.connection.SearchConnectionAdapter;
import com.qqmu.jync.service.migration.es.SearchHttpClient;
import com.qqmu.jync.service.migration.es.SearchHttpClientFactory;
import com.qqmu.jync.util.CryptoUtil;

@ExtendWith(MockitoExtension.class)
class MigrationConnectionServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private MigrationConnectionRepository connections;
    @Mock private MigrationTaskRepository tasks;
    @Mock private CryptoUtil cryptoUtil;
    @Mock private AdminAuditService auditService;
    @Mock private SearchHttpClientFactory clientFactory;
    @Mock private SearchHttpClient client;

    private MigrationConnectionService service;

    @BeforeEach
    void setUp() {
        service = new MigrationConnectionService(connections, tasks, cryptoUtil,
                auditService, new MigrationConnectionAdapterRegistry(
                        List.of(new SearchConnectionAdapter(clientFactory))));
    }

    private MigrationConnectionForm form() {
        MigrationConnectionForm form = new MigrationConnectionForm();
        form.setName("es-prod");
        form.setKind(MigrationConnectionKind.ELASTICSEARCH);
        form.setBaseUrl("http://127.0.0.1:9200/");
        form.setAuthType(MigrationConnectionAuthType.BASIC);
        form.setUsername("elastic");
        form.setVerifyTls(true);
        form.setConnectTimeoutMs(5000);
        form.setRequestTimeoutMs(60000);
        return form;
    }

    /** Renders recorded details with the real bundles, the way the audit page does. */
    private static AuditDetailRenderer renderer() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new AuditDetailRenderer(source);
    }

    private MigrationConnection stored(long id, String passwordEnc) {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(id);
        connection.setName("es-prod");
        connection.setKind(MigrationConnectionKind.ELASTICSEARCH);
        connection.setBaseUrl("http://127.0.0.1:9200");
        connection.setAuthType(MigrationConnectionAuthType.BASIC);
        connection.setUsername("elastic");
        connection.setPasswordEnc(passwordEnc);
        connection.setVerifyTls(true);
        connection.setConnectTimeoutMs(5000);
        connection.setRequestTimeoutMs(60000);
        return connection;
    }

    @Test
    void aNewConnectionEncryptsItsPasswordAndNormalisesTheUrl() {
        when(connections.findByName("es-prod")).thenReturn(Optional.empty());
        when(cryptoUtil.encrypt("secret")).thenReturn("enc:cipher");
        when(connections.save(any(MigrationConnection.class))).thenAnswer(invocation -> {
            MigrationConnection saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        service.save(form(), "secret", null);

        ArgumentCaptor<MigrationConnection> captor =
                ArgumentCaptor.forClass(MigrationConnection.class);
        verify(connections).save(captor.capture());
        MigrationConnection saved = captor.getValue();
        assertThat(saved.getPasswordEnc()).isEqualTo("enc:cipher");
        assertThat(saved.getApiKeyEnc()).isNull();
        assertThat(saved.getBaseUrl()).isEqualTo("http://127.0.0.1:9200");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditAction.MIGRATION_CONNECTION_SAVE),
                detail.capture());
        assertThat(renderer().render(detail.getValue(), Locale.ENGLISH))
                .isEqualTo("Created dedicated-migration connection 'es-prod'"
                        + " (Elasticsearch)");
        assertThat(renderer().render(detail.getValue(), Locale.SIMPLIFIED_CHINESE))
                .isEqualTo("新建专属迁移连接「es-prod」（Elasticsearch）");
    }

    @Test
    void aBlankSecretOnEditKeepsTheStoredOne() {
        MigrationConnection existing = stored(3L, "enc:old");
        MigrationConnectionForm form = form();
        form.setId(3L);
        when(connections.findById(3L)).thenReturn(Optional.of(existing));
        when(connections.findByName("es-prod")).thenReturn(Optional.of(existing));
        when(connections.save(any(MigrationConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(form, "", "");

        ArgumentCaptor<MigrationConnection> captor =
                ArgumentCaptor.forClass(MigrationConnection.class);
        verify(connections).save(captor.capture());
        assertThat(captor.getValue().getPasswordEnc()).isEqualTo("enc:old");
    }

    @Test
    void editingAConnectionMutatesTheLoadedEntitySoJpaUpdatesInsteadOfInserting() {
        MigrationConnection existing = stored(3L, "enc:old");
        existing.setDescription("old");
        MigrationConnectionForm form = form();
        form.setId(3L);
        form.setName("es-prod-renamed");
        form.setDescription("new");
        when(connections.findById(3L)).thenReturn(Optional.of(existing));
        when(connections.findByName("es-prod-renamed")).thenReturn(Optional.empty());
        when(connections.save(any(MigrationConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(form, "", "");

        ArgumentCaptor<MigrationConnection> captor =
                ArgumentCaptor.forClass(MigrationConnection.class);
        verify(connections).save(captor.capture());
        // A fresh entity built from the form has a null @Version, and Spring Data then treats
        // it as new and INSERTs it — an edit would create a duplicate row (or trip the unique
        // name index). Editing has to reuse the loaded instance, which carries the version.
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(existing.getName()).isEqualTo("es-prod-renamed");
        assertThat(existing.getDescription()).isEqualTo("new");
        assertThat(existing.getPasswordEnc()).isEqualTo("enc:old");
    }

    @Test
    void switchingToAnApiKeyClearsTheOldPassword() {
        MigrationConnection existing = stored(3L, "enc:old");
        MigrationConnectionForm form = form();
        form.setId(3L);
        form.setAuthType(MigrationConnectionAuthType.API_KEY);
        when(connections.findById(3L)).thenReturn(Optional.of(existing));
        when(connections.findByName("es-prod")).thenReturn(Optional.of(existing));
        when(cryptoUtil.encrypt("id:key")).thenReturn("enc:key");
        when(connections.save(any(MigrationConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(form, null, "id:key");

        ArgumentCaptor<MigrationConnection> captor =
                ArgumentCaptor.forClass(MigrationConnection.class);
        verify(connections).save(captor.capture());
        assertThat(captor.getValue().getPasswordEnc()).isNull();
        assertThat(captor.getValue().getApiKeyEnc()).isEqualTo("enc:key");
    }

    @Test
    void savingToNoneClearsBothCredentials() {
        MigrationConnection existing = stored(3L, "enc:old");
        existing.setApiKeyEnc("enc:key");
        MigrationConnectionForm form = form();
        form.setId(3L);
        form.setAuthType(MigrationConnectionAuthType.NONE);
        when(connections.findById(3L)).thenReturn(Optional.of(existing));
        when(connections.findByName("es-prod")).thenReturn(Optional.of(existing));
        when(connections.save(any(MigrationConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(form, "new", "new");

        ArgumentCaptor<MigrationConnection> captor =
                ArgumentCaptor.forClass(MigrationConnection.class);
        verify(connections).save(captor.capture());
        assertThat(captor.getValue().getPasswordEnc()).isNull();
        assertThat(captor.getValue().getApiKeyEnc()).isNull();
    }

    @Test
    void invalidInputIsRejectedWithAKey() {
        MigrationConnectionForm noName = form();
        noName.setName(" ");
        assertThatThrownBy(() -> service.save(noName, "x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.name.required");

        MigrationConnectionForm badUrl = form();
        badUrl.setBaseUrl("ftp://host:21");
        assertThatThrownBy(() -> service.save(badUrl, "x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.url.invalid");

        MigrationConnectionForm noUser = form();
        noUser.setUsername(" ");
        assertThatThrownBy(() -> service.save(noUser, "x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.username.required");

        MigrationConnectionForm noKey = form();
        noKey.setAuthType(MigrationConnectionAuthType.API_KEY);
        assertThatThrownBy(() -> service.save(noKey, null, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.apiKey.required");
    }

    @Test
    void aDuplicateNameIsRejected() {
        MigrationConnection other = stored(9L, null);
        MigrationConnectionForm form = form();
        form.setId(3L);
        when(connections.findByName("es-prod")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.save(form, "x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.name.duplicate");
    }

    @Test
    void changingTheKindOfAConnectionUsedByATaskIsRefused() {
        MigrationConnection existing = stored(3L, "enc:old");
        MigrationConnectionForm form = form();
        form.setId(3L);
        form.setKind(MigrationConnectionKind.OPENSEARCH);
        when(connections.findById(3L)).thenReturn(Optional.of(existing));
        when(connections.findByName("es-prod")).thenReturn(Optional.of(existing));
        when(tasks.countBySourceConnectionIdOrTargetConnectionId(3L, 3L)).thenReturn(1L);

        assertThatThrownBy(() -> service.save(form, "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.migration.connection.kind.inUse");
        verify(connections, never()).save(any(MigrationConnection.class));
    }

    @Test
    void deletingAConnectionStillUsedByATaskIsRefused() {
        when(connections.findById(3L)).thenReturn(Optional.of(stored(3L, null)));
        when(tasks.countBySourceConnectionIdOrTargetConnectionId(3L, 3L)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.migration.connection.inUse");
        verify(connections, never()).deleteById(3L);
    }

    @Test
    void deletingAnIdleConnectionIsAudited() {
        when(connections.findById(3L)).thenReturn(Optional.of(stored(3L, null)));
        when(tasks.countBySourceConnectionIdOrTargetConnectionId(3L, 3L)).thenReturn(0L);

        service.delete(3L);

        verify(connections).deleteById(3L);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditAction.MIGRATION_CONNECTION_DELETE),
                detail.capture());
        assertThat(renderer().render(detail.getValue(), Locale.ENGLISH))
                .isEqualTo("Deleted dedicated-migration connection 'es-prod'"
                        + " (Elasticsearch)");
    }

    @Test
    void testingASavedConnectionReportsProductVersionAndCluster() throws Exception {
        MigrationConnection connection = stored(3L, "enc:secret");
        when(connections.findById(3L)).thenReturn(Optional.of(connection));
        when(clientFactory.create(connection)).thenReturn(client);
        JsonNode root = JSON.readTree("{\"cluster_name\":\"prod\","
                + "\"version\":{\"number\":\"7.17.22\",\"distribution\":\"elasticsearch\"}}");
        when(client.get("/")).thenReturn(root);

        MigrationConnectionTestResult result = service.test(3L);

        assertThat(result.success()).isTrue();
        assertThat(result.product()).isEqualTo("elasticsearch");
        assertThat(result.version()).isEqualTo("7.17.22");
        assertThat(result.clusterName()).isEqualTo("prod");
    }

    @Test
    void discoveryFiltersSystemIndicesAndHonoursThePattern() throws Exception {
        MigrationConnection connection = stored(3L, null);
        when(connections.findById(3L)).thenReturn(Optional.of(connection));
        when(clientFactory.create(connection)).thenReturn(client);
        JsonNode cat = JSON.readTree("["
                + "{\"index\":\"orders-2026\",\"docs.count\":\"12\",\"store.size\":\"1kb\","
                + "\"status\":\"open\",\"health\":\"green\"},"
                + "{\"index\":\"users\",\"docs.count\":\"3\",\"store.size\":\"1kb\","
                + "\"status\":\"open\",\"health\":\"green\"},"
                + "{\"index\":\".security\",\"docs.count\":\"9\",\"store.size\":\"1kb\","
                + "\"status\":\"open\",\"health\":\"green\"}]");
        when(client.get(startsWith("/_cat/indices"))).thenReturn(cat);

        List<MigrationDiscoveryItem> indices =
                service.listIndices(3L, "orders-*");

        assertThat(indices).singleElement().satisfies(index -> {
            assertThat(index.name()).isEqualTo("orders-2026");
            assertThat(index.docsCount()).isEqualTo(12L);
        });
    }
}
