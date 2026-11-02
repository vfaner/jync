package com.qqmu.jync.service.migration.es;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.service.migration.MigrationPrecheckResult;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Level;

@ExtendWith(MockitoExtension.class)
class EsToEasysearchPrecheckTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private SearchHttpClientFactory clientFactory;
    @Mock private SearchHttpClient sourceClient;
    @Mock private SearchHttpClient targetClient;

    private EsToEasysearchHandler handler;
    private MigrationConnection source;
    private MigrationConnection target;
    private MigrationTask task;

    @BeforeEach
    void setUp() {
        handler = new EsToEasysearchHandler(clientFactory);
        source = connection(1L, MigrationConnectionKind.ELASTICSEARCH, "http://source:9200");
        target = connection(2L, MigrationConnectionKind.EASYSEARCH, "http://target:9200");
        task = new MigrationTask();
        task.setId(9L);
        task.setType(MigrationType.ES_TO_EASYSEARCH);
        lenient().when(clientFactory.create(source)).thenReturn(sourceClient);
        lenient().when(clientFactory.create(target)).thenReturn(targetClient);
    }

    private static MigrationConnection connection(long id, MigrationConnectionKind kind,
                                                  String url) {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(id);
        connection.setName("conn-" + id);
        connection.setKind(kind);
        connection.setBaseUrl(url);
        connection.setAuthType(MigrationConnectionAuthType.NONE);
        return connection;
    }

    private static JsonNode options(String json) throws Exception {
        return EsToEasysearchOptions.parse((ObjectNode) JSON.readTree(json)).toJson();
    }

    private static JsonNode json(String value) throws Exception {
        return JSON.readTree(value);
    }

    private static boolean has(MigrationPrecheckResult result, Level level, String code) {
        return result.checks().stream()
                .anyMatch(check -> check.level() == level && check.code().equals(code));
    }

    private void stubOneIndex(String index, JsonNode mapping) throws Exception {
        when(sourceClient.get(startsWith("/_cat/indices"))).thenReturn(json("[{\"index\":\""
                + index + "\",\"docs.count\":\"4\",\"store.size\":\"1kb\","
                + "\"status\":\"open\",\"health\":\"green\"}]"));
        when(sourceClient.get("/" + index + "/_settings")).thenReturn(json("{\"" + index
                + "\":{\"settings\":{\"index\":{\"number_of_shards\":\"1\","
                + "\"number_of_replicas\":\"0\"}}}}"));
        when(sourceClient.get("/" + index + "/_mapping")).thenReturn(mapping);
    }

    @Test
    void anUnreachableSourceBecomesAnErrorCheckInsteadOfAnException() {
        when(sourceClient.get(startsWith("/_cat/indices"))).thenThrow(
                new MigrationHttpException("GET", "/_cat/indices", 0, "I/O error", "refused"));

        MigrationPrecheckResult result = handler.precheck(task, optionsOrEmpty(), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.source.unreachable")).isTrue();
    }

    @Test
    void aSourceDisabledMappingBlocksTheIndex() throws Exception {
        stubOneIndex("orders", json("{\"orders\":{\"mappings\":{"
                + "\"_source\":{\"enabled\":false},\"properties\":{}}}}"));

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"probeCompatibility\":false}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.index.noSource")).isTrue();
    }

    @Test
    void unsupportedFieldTypesWarnWithoutFailingTheRun() throws Exception {
        stubOneIndex("orders", json("{\"orders\":{\"mappings\":{\"_source\":{\"enabled\":true},"
                + "\"properties\":{\"embedding\":{\"type\":\"knn_vector\",\"dims\":8},"
                + "\"tags\":{\"type\":\"keyword\"}}}}}"));
        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"probeCompatibility\":false}"), source, target);

        assertThat(result.passed()).isTrue();
        assertThat(has(result, Level.WARN, "migration.precheck.index.mapping")).isTrue();
        assertThat(result.checks().stream()
                .filter(check -> check.code().equals("migration.precheck.index.mapping"))
                .findFirst().orElseThrow().detail()).contains("knn_vector");
    }

    @Test
    void failIfExistsIsAnErrorWhenTheTargetIndexAlreadyExists() throws Exception {
        stubOneIndex("orders", json("{\"orders\":{\"mappings\":{\"_source\":{\"enabled\":true}}}}"));
        when(targetClient.exists("/orders")).thenReturn(true);

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"writeMode\":\"FAIL_IF_EXISTS\",\"probeCompatibility\":false}"),
                source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.index.targetExists")).isTrue();
    }

    @Test
    void aPassingCompatibilityProbeIsRecordedAndCleansUpAfterItself() throws Exception {
        stubOneIndex("orders", json("{\"orders\":{\"mappings\":{\"_source\":{\"enabled\":true},"
                + "\"properties\":{\"id\":{\"type\":\"keyword\"}}}}}"));
        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"probeCompatibility\":true}"), source, target);

        assertThat(result.passed()).isTrue();
        assertThat(has(result, Level.OK, "migration.precheck.compat.ok")).isTrue();
        verify(targetClient).putJson(startsWith("/jync-precheck-"), any());
        verify(targetClient).delete(startsWith("/jync-precheck-"));
    }

    @Test
    void aFailedCompatibilityProbeIsAnErrorWithTheServerDetail() throws Exception {
        stubOneIndex("orders", json("{\"orders\":{\"mappings\":{\"_source\":{\"enabled\":true}}}}"));
        when(targetClient.putJson(startsWith("/jync-precheck-"), any())).thenThrow(
                new MigrationHttpException("PUT", "/jync-precheck-x", 400,
                        "400 Bad Request", "mapper_parsing_exception"));

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"probeCompatibility\":true}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.compat.failed")).isTrue();
        assertThat(result.checks().stream()
                .filter(check -> check.code().equals("migration.precheck.compat.failed"))
                .findFirst().orElseThrow().detail()).contains("mapper_parsing_exception");
    }

    @Test
    void aPatternThatMatchesNothingWarnsRatherThanPassingSilently() throws Exception {
        when(sourceClient.get(startsWith("/_cat/indices"))).thenReturn(json(
                "[{\"index\":\".security\",\"docs.count\":\"1\",\"store.size\":\"1kb\","
                        + "\"status\":\"open\",\"health\":\"green\"}]"));

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"indicesText\":\"orders-*\",\"probeCompatibility\":false}"),
                source, target);

        assertThat(result.passed()).isTrue();
        assertThat(has(result, Level.WARN, "migration.precheck.discovery.empty")).isTrue();
    }

    private static JsonNode optionsOrEmpty() {
        try {
            return options("{}");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
