package com.qqmu.jync.service.migration.es;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.service.migration.MigrationCancellation;
import com.qqmu.jync.service.migration.MigrationProgressListener;
import com.qqmu.jync.service.migration.MigrationRunResult;

@ExtendWith(MockitoExtension.class)
class EsToEasysearchEngineTest {

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

    private static JsonNode options(String overrides) throws Exception {
        ObjectNode base = (ObjectNode) JSON.readTree(
                "{\"probeCompatibility\":false,\"verifyCount\":false,\"verifySampleSize\":0}");
        base.setAll((ObjectNode) JSON.readTree(overrides));
        return EsToEasysearchOptions.parse(base).toJson();
    }

    private static JsonNode json(String value) throws Exception {
        return JSON.readTree(value);
    }

    private void stubCat(String... indices) throws Exception {
        ArrayNode rows = JSON.createArrayNode();
        for (String index : indices) {
            ObjectNode row = rows.addObject();
            row.put("index", index);
            row.put("docs.count", "1");
            row.put("store.size", "1kb");
            row.put("status", "open");
            row.put("health", "green");
        }
        when(sourceClient.get(startsWith("/_cat/indices"))).thenReturn(rows);
    }

    private void stubSourceIndex(String index) throws Exception {
        when(sourceClient.get("/" + index + "/_settings")).thenReturn(json("{\"" + index
                + "\":{\"settings\":{\"index\":{\"number_of_shards\":\"1\","
                + "\"uuid\":\"u\"}}}}"));
        when(sourceClient.get("/" + index + "/_mapping")).thenReturn(json("{\"" + index
                + "\":{\"mappings\":{\"_source\":{\"enabled\":true},"
                + "\"properties\":{\"name\":{\"type\":\"keyword\"}}}}}"));
    }

    private static ObjectNode page(String scrollId, JsonNode... hits) {
        ObjectNode page = JSON.createObjectNode();
        page.put("_scroll_id", scrollId);
        ObjectNode inner = page.putObject("hits");
        ArrayNode array = inner.putArray("hits");
        for (JsonNode hit : hits) {
            array.add(hit);
        }
        return page;
    }

    private static ObjectNode hit(String id, String routing, String sourceJson)
            throws Exception {
        ObjectNode hit = JSON.createObjectNode();
        hit.put("_id", id);
        if (routing != null) {
            hit.put("_routing", routing);
        }
        hit.set("_source", JSON.readTree(sourceJson));
        return hit;
    }

    private static ObjectNode count(long value) {
        ObjectNode count = JSON.createObjectNode();
        count.put("count", value);
        return count;
    }

    private MigrationRunResult run(JsonNode options) {
        return handler.run(task, options, source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);
    }

    @Test
    void aPatternThatMatchesNothingFailsTheRunInsteadOfReportingSuccess() throws Exception {
        // A typo in the pattern used to end as "SUCCESS indices=0/0": the list badge said the
        // migration worked while nothing had been copied.
        stubCat();

        MigrationRunResult result = run(options("{}"));

        assertThat(result.success()).as(result.toString()).isFalse();
        assertThat(result.cancelled()).isFalse();
        assertThat(result.indicesTotal()).isZero();
        assertThat(result.message()).contains("no indices matched");
    }

    @Test
    void overwriteDeletesRebuildsAndCopiesEveryPagePreservingIdAndRouting() throws Exception {
        stubCat("orders");
        stubSourceIndex("orders");
        when(targetClient.exists("/orders")).thenReturn(true);
        when(sourceClient.search(startsWith("/orders/_search?scroll="), any()))
                .thenReturn(page("s1",
                        hit("1", "tenant-1", "{\"name\":\"a\"}"),
                        hit("2", null, "{\"name\":\"b\"}")));
        when(sourceClient.postJson(eq("/_search/scroll"), any()))
                .thenReturn(page("s1"));
        when(targetClient.bulk(anyList())).thenReturn(BulkResult.success());
        when(sourceClient.get("/orders/_count")).thenReturn(count(2));
        when(targetClient.get("/orders/_count")).thenReturn(count(2));

        MigrationRunResult result = run(options("{\"verifyCount\":true}"));

        assertThat(result.success()).as(result.toString()).isTrue();
        assertThat(result.cancelled()).isFalse();
        assertThat(result.indicesTotal()).isEqualTo(1);
        assertThat(result.indicesSucceeded()).isEqualTo(1);
        assertThat(result.indicesFailed()).isZero();
        assertThat(result.documentsRead()).isEqualTo(2);
        assertThat(result.documentsWritten()).isEqualTo(2);

        InOrder order = inOrder(targetClient);
        order.verify(targetClient).delete("/orders");
        order.verify(targetClient).putJson(eq("/orders"), any());
        order.verify(targetClient).bulk(anyList());
        order.verify(targetClient).postJson(eq("/orders/_refresh"), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(targetClient).bulk(captor.capture());
        List<String> lines = captor.getValue();
        assertThat(lines).hasSize(4);
        // Bulk action metadata spells the routing parameter without the underscore; ES 7.17
        // rejects "_routing" with "Action/metadata line [n] contains an unknown parameter".
        assertThat(lines.get(0)).contains("\"_index\":\"orders\"")
                .contains("\"_id\":\"1\"").contains("\"routing\":\"tenant-1\"")
                .doesNotContain("\"_routing\"");
        assertThat(lines.get(1)).contains("\"name\":\"a\"");
        assertThat(lines.get(2)).contains("\"_id\":\"2\"").doesNotContain("_routing");

        verify(sourceClient).clearScroll("s1");
    }

    @Test
    void appendCreatesAMissingTargetWithoutDeletingAnything() throws Exception {
        stubCat("orders");
        stubSourceIndex("orders");
        when(targetClient.exists("/orders")).thenReturn(false);
        when(sourceClient.search(startsWith("/orders/_search?scroll="), any()))
                .thenReturn(page("s1", hit("1", null, "{\"name\":\"a\"}")));
        when(sourceClient.postJson(eq("/_search/scroll"), any())).thenReturn(page("s1"));
        when(targetClient.bulk(anyList())).thenReturn(BulkResult.success());

        MigrationRunResult result = run(options("{\"writeMode\":\"APPEND\"}"));

        assertThat(result.success()).isTrue();
        verify(targetClient, never()).delete(anyString());
        verify(targetClient).putJson(eq("/orders"), any());
        verify(targetClient).postJson(eq("/orders/_refresh"), any());
    }

    @Test
    void failIfExistsSkipsThatIndexButContinuesWithTheNext() throws Exception {
        stubCat("orders", "users");
        stubSourceIndex("orders");
        stubSourceIndex("users");
        when(targetClient.exists("/orders")).thenReturn(true);
        when(targetClient.exists("/users")).thenReturn(false);
        when(sourceClient.search(startsWith("/users/_search?scroll="), any()))
                .thenReturn(page("u1", hit("1", null, "{\"name\":\"u\"}")));
        when(sourceClient.postJson(eq("/_search/scroll"), any())).thenReturn(page("u1"));
        when(targetClient.bulk(anyList())).thenReturn(BulkResult.success());

        MigrationRunResult result = run(
                options("{\"writeMode\":\"FAIL_IF_EXISTS\"}"));

        assertThat(result.success()).isFalse();
        assertThat(result.indicesFailed()).isEqualTo(1);
        assertThat(result.indicesSucceeded()).isEqualTo(1);
        assertThat(result.documentsWritten()).isEqualTo(1);
        assertThat(result.indices().get(0).status())
                .isEqualTo(MigrationRunResult.Status.FAILED);
        assertThat(result.indices().get(0).message()).contains("exists");
        verify(targetClient, never()).delete(anyString());
    }

    @Test
    void aFailedBulkItemFailsTheIndexWithoutThrowing() throws Exception {
        stubCat("orders");
        stubSourceIndex("orders");
        when(targetClient.exists("/orders")).thenReturn(false);
        when(sourceClient.search(startsWith("/orders/_search?scroll="), any()))
                .thenReturn(page("s1", hit("1", null, "{\"name\":\"a\"}")));
        when(targetClient.bulk(anyList())).thenReturn(new BulkResult(true,
                List.of(new BulkResult.FailedItem("1", "mapper_parsing_exception"))));

        MigrationRunResult result = run(options("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.indicesFailed()).isEqualTo(1);
        assertThat(result.indices().get(0).message())
                .contains("1").contains("mapper_parsing_exception");
        verify(sourceClient).clearScroll("s1");
        verify(targetClient, never()).postJson(eq("/orders/_refresh"), any());
    }

    @Test
    void cancellationBetweenPagesClearsTheScrollAndReturnsCancelled() throws Exception {
        stubCat("orders");
        stubSourceIndex("orders");
        when(targetClient.exists("/orders")).thenReturn(false);
        when(sourceClient.search(startsWith("/orders/_search?scroll="), any()))
                .thenReturn(page("s1", hit("1", null, "{\"name\":\"a\"}")));
        MigrationCancellation token = new MigrationCancellation();
        when(targetClient.bulk(anyList())).thenAnswer(invocation -> {
            token.cancel();
            return BulkResult.success();
        });

        MigrationRunResult result = handler.run(task, options("{}"), source, target, token,
                MigrationProgressListener.NONE);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(result.indices().get(0).status())
                .isEqualTo(MigrationRunResult.Status.CANCELLED);
        verify(sourceClient).clearScroll("s1");
    }

    @Test
    void aSampleMismatchIsAWarningWhileTheRunStaysSuccessful() throws Exception {
        stubCat("orders");
        stubSourceIndex("orders");
        when(targetClient.exists("/orders")).thenReturn(false);
        when(sourceClient.search(startsWith("/orders/_search?scroll="), any()))
                .thenReturn(page("s1", hit("1", null, "{\"name\":\"a\"}")));
        when(sourceClient.postJson(eq("/_search/scroll"), any())).thenReturn(page("s1"));
        when(targetClient.bulk(anyList())).thenReturn(BulkResult.success());
        when(sourceClient.get("/orders/_count")).thenReturn(count(1));
        when(targetClient.get("/orders/_count")).thenReturn(count(1));
        when(targetClient.postJson(eq("/orders/_refresh"), any())).thenReturn(null);
        when(sourceClient.search(startsWith("/orders/_search?size="), any()))
                .thenReturn(page(null, hit("1", null, "{\"name\":\"a\"}")));
        when(targetClient.postJson(eq("/orders/_mget"), any()))
                .thenReturn(json("{\"docs\":[{\"_id\":\"1\",\"found\":true,"
                        + "\"_source\":{\"name\":\"b\"}}]}"));

        MigrationRunResult result = run(options("{\"verifyCount\":true,"
                + "\"verifySampleSize\":1}"));

        assertThat(result.success()).as(result.toString()).isTrue();
        assertThat(result.message()).contains("WARN");
    }

    @Test
    void aTargetThatRefusesTheIndexFailsOnlyThatIndex() throws Exception {
        stubCat("orders");
        stubSourceIndex("orders");
        when(targetClient.exists("/orders")).thenReturn(false);
        when(targetClient.putJson(eq("/orders"), any())).thenThrow(
                new MigrationHttpException("PUT", "/orders", 400, "400 Bad Request",
                        "mapper_parsing_exception: bad field"));

        MigrationRunResult result = run(options("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.indicesFailed()).isEqualTo(1);
        assertThat(result.indices().get(0).message()).contains("mapper_parsing_exception");
        verify(sourceClient, never()).clearScroll(anyString());
    }

    @Test
    void aliasesAreCopiedWhenTheOptionIsOn() throws Exception {
        stubCat("orders");
        stubSourceIndex("orders");
        when(sourceClient.get("/orders/_alias")).thenReturn(json("{\"orders\":{\"aliases\":"
                + "{\"orders-current\":{\"is_write_index\":true}}}}"));
        when(targetClient.exists("/orders")).thenReturn(false);
        when(targetClient.putJson(eq("/orders"), any())).thenReturn(null);
        when(targetClient.putJson(eq("/orders/_alias/orders-current"), any()))
                .thenReturn(null);
        when(sourceClient.search(startsWith("/orders/_search?scroll="), any()))
                .thenReturn(page("s1", hit("1", null, "{\"name\":\"a\"}")));
        when(sourceClient.postJson(eq("/_search/scroll"), any())).thenReturn(page("s1"));
        when(targetClient.bulk(anyList())).thenReturn(BulkResult.success());

        MigrationRunResult result = run(options("{\"copyAliases\":true}"));

        assertThat(result.success()).as(result.toString()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(targetClient).putJson(eq("/orders/_alias/orders-current"), body.capture());
        assertThat(body.getValue().toString()).contains("is_write_index");
    }
}
