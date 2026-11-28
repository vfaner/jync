package com.qqmu.jync.service.migration.es;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.service.migration.MigrationCancellation;
import com.qqmu.jync.service.migration.MigrationCancelledException;
import com.qqmu.jync.service.migration.MigrationProgressListener;
import com.qqmu.jync.service.migration.MigrationRunResult;
import com.qqmu.jync.service.migration.MigrationRunResult.IndexResult;
import com.qqmu.jync.service.migration.MigrationRunResult.Status;

/**
 * Copies indexes from an Elasticsearch/OpenSearch cluster to Easysearch over the ES 7 REST API.
 *
 * <p>Every index is processed independently: one failure does not roll back documents that were
 * already written, and later indexes continue unless {@code continueOnError} is false. A
 * cancelled or failed index keeps its partial read/write counts in the run history.
 */
final class EsToEasysearchEngine {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SearchHttpClientFactory clientFactory;

    EsToEasysearchEngine(SearchHttpClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    MigrationRunResult run(MigrationTask task, JsonNode optionsNode,
                           MigrationConnection source, MigrationConnection target,
                           MigrationCancellation cancellation,
                           MigrationProgressListener progress) {
        EsToEasysearchOptions options;
        try {
            options = EsToEasysearchOptions.parse(optionsNode);
        } catch (RuntimeException e) {
            return failedRun("invalid options: " + describe(e));
        }

        SearchHttpClient sourceClient;
        SearchHttpClient targetClient;
        try {
            sourceClient = clientFactory.create(source);
            targetClient = clientFactory.create(target);
        } catch (RuntimeException e) {
            return failedRun(describe(e));
        }

        List<String> patterns = options.patterns();
        List<EsIndexSupport.IndexSummary> discovered;
        try {
            discovered = EsIndexSupport.summarise(
                    sourceClient.get(EsIndexSupport.CAT_INDICES), patterns,
                    EsIndexSupport.asksForSystemIndex(patterns));
        } catch (RuntimeException e) {
            return failedRun("index discovery failed: " + describe(e));
        }

        List<String> runWarnings = new ArrayList<>();
        List<EsIndexSupport.IndexSummary> indices = discovered;
        if (indices.isEmpty()) {
            runWarnings.add("WARN: no indices matched " + String.join(", ", patterns));
        } else if (indices.size() > options.getMaxIndices()) {
            runWarnings.add("WARN: " + indices.size() + " indices matched, only the first "
                    + options.getMaxIndices() + " will be migrated");
            indices = indices.subList(0, options.getMaxIndices());
        }

        List<IndexResult> results = new ArrayList<>();
        boolean cancelled = false;
        // Two connection rows may point at the SAME cluster (a common way to test a copy).
        // With the default SAME naming and OVERWRITE this would delete the source index
        // before a single document is read, and the run would report SUCCESS 0/0.
        boolean sameEndpoint = sameCluster(sourceClient, targetClient, source, target);
        for (EsIndexSupport.IndexSummary summary : indices) {
            String targetIndex;
            try {
                targetIndex = options.targetName(summary.index());
            } catch (IllegalArgumentException e) {
                results.add(new IndexResult(summary.index(), summary.index(), Status.FAILED,
                        0, 0, e.getMessage()));
                if (!options.isContinueOnError()) {
                    break;
                }
                continue;
            }

            Progress progressCounters = new Progress();
            try {
                cancellation.check();
                results.add(migrateIndex(options, sourceClient, targetClient, summary.index(),
                        targetIndex, sameEndpoint, cancellation, progressCounters, progress));
            } catch (MigrationCancelledException e) {
                results.add(new IndexResult(summary.index(), targetIndex, Status.CANCELLED,
                        progressCounters.read, progressCounters.written, "cancelled"));
                cancelled = true;
                break;
            } catch (RuntimeException e) {
                results.add(new IndexResult(summary.index(), targetIndex, Status.FAILED,
                        progressCounters.read, progressCounters.written, describe(e)));
                if (!options.isContinueOnError()) {
                    break;
                }
            }
        }

        long documentsRead = results.stream().mapToLong(IndexResult::documentsRead).sum();
        long documentsWritten = results.stream().mapToLong(IndexResult::documentsWritten).sum();
        int succeeded = (int) results.stream()
                .filter(result -> result.status() == Status.SUCCESS).count();
        int failed = (int) results.stream()
                .filter(result -> result.status() == Status.FAILED).count();

        List<String> messages = new ArrayList<>(runWarnings);
        for (IndexResult result : results) {
            if (result.message() != null && !result.message().isBlank()) {
                messages.add(result.sourceIndex() + ": " + result.message());
            }
        }

        // An empty match is a failure, not a no-op: the usual cause is a mistyped pattern, and
        // reporting SUCCESS 0/0 would tell the user the migration worked when nothing was copied.
        return new MigrationRunResult(!cancelled && failed == 0 && !indices.isEmpty(),
                cancelled, indices.size(),
                succeeded, failed, documentsRead, documentsWritten,
                messages.isEmpty() ? null : String.join("; ", messages), results);
    }

    private IndexResult migrateIndex(EsToEasysearchOptions options,
                                     SearchHttpClient sourceClient,
                                     SearchHttpClient targetClient,
                                     String sourceIndex, String targetIndex,
                                     boolean sameEndpoint,
                                     MigrationCancellation cancellation,
                                     Progress progressCounters,
                                     MigrationProgressListener progress) {
        String sourcePath = "/" + segment(sourceIndex);
        String targetPath = "/" + segment(targetIndex);
        if (sameEndpoint
                && options.getWriteMode() == EsToEasysearchOptions.WriteMode.OVERWRITE
                && targetIndex.equals(sourceIndex)) {
            return new IndexResult(sourceIndex, targetIndex, Status.FAILED, 0, 0,
                    "refusing to overwrite the source index on the same cluster");
        }
        List<String> warnings = new ArrayList<>();

        JsonNode settings = options.isCopySettings()
                ? sourceClient.get(sourcePath + "/_settings") : null;
        JsonNode mapping = options.isCopyMappings()
                ? sourceClient.get(sourcePath + "/_mapping") : null;
        JsonNode aliasResponse = options.isCopyAliases()
                ? sourceClient.get(sourcePath + "/_alias") : null;

        boolean exists = targetClient.exists(targetPath);
        if (exists) {
            if (options.getWriteMode() == EsToEasysearchOptions.WriteMode.FAIL_IF_EXISTS) {
                return new IndexResult(sourceIndex, targetIndex, Status.FAILED, 0, 0,
                        "target index already exists: " + targetIndex);
            }
            if (options.getWriteMode() == EsToEasysearchOptions.WriteMode.OVERWRITE) {
                targetClient.delete(targetPath);
                exists = false;
            }
        }

        if (!exists) {
            ObjectNode body = JSON.createObjectNode();
            if (settings != null) {
                body.set("settings", EsIndexSupport.cleanSettings(settings, sourceIndex));
            }
            if (mapping != null) {
                body.set("mappings", EsIndexSupport.mappingFor(mapping, sourceIndex));
            }
            targetClient.putJson(targetPath, body);
        }

        if (aliasResponse != null) {
            copyAliases(targetClient, targetPath, sourceIndex, aliasResponse, warnings);
        }

        copyDocuments(options, sourceClient, targetClient, sourcePath, targetPath, sourceIndex,
                cancellation, progressCounters, progress);

        targetClient.postJson(targetPath + "/_refresh", JSON.createObjectNode());

        if (options.isVerifyCount() && !verifyCount(options, sourceClient, targetClient,
                sourcePath, targetPath, progressCounters)) {
            return new IndexResult(sourceIndex, targetIndex, Status.FAILED, progressCounters.read,
                    progressCounters.written,
                    "document count mismatch: source and target differ");
        }

        if (options.getVerifySampleSize() > 0) {
            int mismatches = verifySample(options, sourceClient, targetClient, sourcePath,
                    targetPath);
            if (mismatches > 0) {
                warnings.add("WARN: " + mismatches
                        + " sampled documents differ between source and target");
            }
        }

        return new IndexResult(sourceIndex, targetIndex, Status.SUCCESS, progressCounters.read,
                progressCounters.written, warnings.isEmpty() ? null : String.join("; ", warnings));
    }

    private static void copyAliases(SearchHttpClient targetClient, String targetPath,
                                    String sourceIndex, JsonNode aliasResponse,
                                    List<String> warnings) {
        JsonNode aliases = aliasResponse.path(sourceIndex).path("aliases");
        if (!aliases.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> iterator = aliases.properties().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> alias = iterator.next();
            try {
                targetClient.putJson(targetPath + "/_alias/" + segment(alias.getKey()),
                        alias.getValue());
            } catch (RuntimeException e) {
                // A conflicting alias must not hide the documents that were already copied.
                warnings.add("WARN: alias " + alias.getKey() + ": " + describe(e));
            }
        }
    }

    private static void copyDocuments(EsToEasysearchOptions options,
                                      SearchHttpClient sourceClient,
                                      SearchHttpClient targetClient,
                                      String sourcePath, String targetPath, String sourceIndex,
                                      MigrationCancellation cancellation,
                                      Progress counters, MigrationProgressListener progress) {
        ObjectNode query = JSON.createObjectNode();
        query.put("size", scrolledPageSize(sourceClient, sourcePath, options));
        ArrayNode sort = query.putArray("sort");
        sort.add("_doc");
        query.put("_source", true);
        // A hit's metadata fields are only built when stored_fields is non-empty; asking for
        // _source alone yields hits without "_routing", and a custom-routed document copied
        // without it lands on a different shard than its join parent.
        query.putArray("stored_fields").add("_routing");
        query.putObject("query").putObject("match_all");

        String searchPath = sourcePath + "/_search?scroll=" + options.getScrollTtl();
        JsonNode page = sourceClient.search(searchPath, query);
        String scrollId = page == null ? null : page.path("_scroll_id").asString(null);
        try {
            while (page != null) {
                JsonNode hits = page.path("hits").path("hits");
                if (!hits.isArray() || hits.isEmpty()) {
                    break;
                }
                List<String> lines = new ArrayList<>();
                long chunkBytes = 0;
                int chunkDocuments = 0;
                for (JsonNode hit : hits) {
                    String id = hit.path("_id").asString(null);
                    JsonNode document = hit.get("_source");
                    if (document == null || document.isNull()) {
                        throw new IllegalStateException(
                                "source document " + id + " has no _source");
                    }
                    ObjectNode action = JSON.createObjectNode();
                    ObjectNode metadata = action.putObject("index");
                    metadata.put("_index", targetPath.substring(1));
                    if (id != null && !id.isEmpty()) {
                        metadata.put("_id", id);
                    }
                    JsonNode routing = hit.get("_routing");
                    if (routing != null && !routing.isNull()) {
                        // Bulk action metadata spells routing without the leading underscore.
                        metadata.set("routing", routing);
                    }
                    String actionLine = write(action);
                    String documentLine = write(document);
                    long pairBytes = actionLine.getBytes(StandardCharsets.UTF_8).length
                            + documentLine.getBytes(StandardCharsets.UTF_8).length + 2;
                    if (chunkDocuments > 0
                            && chunkBytes + pairBytes > options.getMaxBulkBytes()) {
                        flush(targetClient, lines, chunkDocuments, counters);
                        lines = new ArrayList<>();
                        chunkBytes = 0;
                        chunkDocuments = 0;
                    }
                    lines.add(actionLine);
                    lines.add(documentLine);
                    chunkBytes += pairBytes;
                    chunkDocuments++;
                    counters.read++;
                }
                flush(targetClient, lines, chunkDocuments, counters);
                cancellation.check();
                progress.onProgress(sourceIndex, counters.read, counters.written);

                JsonNode next = sourceClient.postJson("/_search/scroll",
                        scrollBody(scrollId, options.getScrollTtl()));
                scrollId = next == null ? scrollId
                        : next.path("_scroll_id").asString(scrollId);
                page = next;
            }
        } finally {
            if (scrollId != null) {
                try {
                    sourceClient.clearScroll(scrollId);
                } catch (RuntimeException ignored) {
                    // The scroll may have expired; the documents already copied stay valid.
                }
            }
        }
    }

    private static void flush(SearchHttpClient targetClient, List<String> lines,
                              int documents, Progress counters) {
        if (lines.isEmpty()) {
            return;
        }
        BulkResult result = targetClient.bulk(lines);
        if (result != null && result.hasErrors()) {
            // A bulk request is not atomic: the items without an error did reach the target,
            // and the run history should say so. Every failed item is one document of this
            // batch, so the successes are the rest. An errors flag with no parsed items means
            // the response shape was unreadable, not that everything succeeded — count nothing.
            if (!result.failedItems().isEmpty()) {
                counters.written += Math.max(0, documents - result.failedItems().size());
            }
            BulkResult.FailedItem failure = result.failedItems().isEmpty() ? null
                    : result.failedItems().get(0);
            String detail = failure == null ? "unknown item"
                    : "id=" + failure.id() + " " + failure.reason();
            throw new IllegalStateException("bulk failed: " + detail);
        }
        counters.written += documents;
    }

    private static boolean verifyCount(EsToEasysearchOptions options,
                                       SearchHttpClient sourceClient,
                                       SearchHttpClient targetClient,
                                       String sourcePath, String targetPath,
                                       Progress counters) {
        long targetCount = count(targetClient, targetPath);
        if (options.getWriteMode() == EsToEasysearchOptions.WriteMode.APPEND) {
            return targetCount >= counters.written;
        }
        return count(sourceClient, sourcePath) == targetCount;
    }

    private static long count(SearchHttpClient client, String indexPath) {
        return client.get(indexPath + "/_count").path("count").asLong(0);
    }

    /**
     * The scroll page size. One page is buffered whole — the response string AND its parsed
     * JSON tree — before {@code maxBulkBytes} can split the target writes, so the configured
     * batch size alone does not bound memory: 1000 documents of 1 MB each is a 2 GB page.
     * The index stats give the average document size, and the page is capped at roughly one
     * bulk budget's worth. Stats that are unavailable (permissions, version, empty index)
     * fall back to the configured size.
     */
    static int scrolledPageSize(SearchHttpClient client, String indexPath,
                                EsToEasysearchOptions options) {
        try {
            JsonNode total = client.get(indexPath + "/_stats").path("_all").path("total");
            long bytes = total.path("store").path("size_in_bytes").asLong(0);
            long docs = total.path("docs").path("count").asLong(0);
            if (bytes > 0 && docs > 0) {
                long average = Math.max(1, bytes / docs);
                long cap = Math.max(1, options.getMaxBulkBytes() / average);
                return (int) Math.max(1, Math.min(options.getBatchSize(), cap));
            }
        } catch (RuntimeException e) {
            // Discovery must not fail because a stats endpoint is restricted.
        }
        return options.getBatchSize();
    }

    /** Returns how many sampled documents have a target {@code _source} unlike the source's. */
    private static int verifySample(EsToEasysearchOptions options,
                                    SearchHttpClient sourceClient,
                                    SearchHttpClient targetClient,
                                    String sourcePath, String targetPath) {
        ObjectNode query = JSON.createObjectNode();
        query.put("size", options.getVerifySampleSize());
        query.put("_source", true);
        // Same as the scroll request: without stored_fields the sampled hit carries no
        // "_routing", and its _mget ref would point at the default shard.
        query.putArray("stored_fields").add("_routing");
        query.putObject("query").putObject("match_all");

        JsonNode sample = sourceClient.search(
                sourcePath + "/_search?size=" + options.getVerifySampleSize(), query);
        JsonNode hits = sample == null ? null : sample.path("hits").path("hits");
        if (hits == null || !hits.isArray() || hits.isEmpty()) {
            return 0;
        }

        // The request must carry each sampled document's routing: an id-only _mget looks the
        // document up on the default shard, so a custom-routed document comes back as
        // not-found and the run warns about a difference that does not exist. Docs are also
        // paired by position (the _mget contract) because the same _id may legitimately exist
        // under two routing values.
        ArrayNode refs = JSON.createArrayNode();
        List<JsonNode> expected = new ArrayList<>();
        for (JsonNode hit : hits) {
            String id = hit.path("_id").asString("");
            if (id.isEmpty()) {
                continue;
            }
            ObjectNode ref = JSON.createObjectNode();
            ref.put("_id", id);
            JsonNode routing = hit.get("_routing");
            if (routing != null && !routing.isNull()) {
                ref.set("routing", routing);
            }
            refs.add(ref);
            expected.add(hit.get("_source"));
        }
        if (refs.isEmpty()) {
            return 0;
        }

        ObjectNode body = JSON.createObjectNode();
        body.set("docs", refs);
        JsonNode response = targetClient.postJson(targetPath + "/_mget", body);
        JsonNode docs = response == null ? null : response.path("docs");
        if (docs == null || !docs.isArray()) {
            return expected.size();
        }

        int mismatches = 0;
        for (int i = 0; i < expected.size(); i++) {
            if (i >= docs.size()) {
                mismatches++;
                continue;
            }
            JsonNode doc = docs.get(i);
            if (!doc.path("found").asBoolean(false)
                    || !Objects.equals(expected.get(i), doc.get("_source"))) {
                mismatches++;
            }
        }
        return mismatches;
    }

    private static ObjectNode scrollBody(String scrollId, String ttl) {
        ObjectNode body = JSON.createObjectNode();
        body.put("scroll", ttl);
        body.put("scroll_id", scrollId == null ? "" : scrollId);
        return body;
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("Could not serialize a bulk line", e);
        }
    }

    private static MigrationRunResult failedRun(String message) {
        return new MigrationRunResult(false, false, 0, 0, 0, 0, 0, message, List.of());
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class Progress {
        long read;
        long written;
    }

    /**
     * Cluster identity first: two URLs can name the same cluster (host alias, loopback vs IP,
     * a load balancer), and deleting the source index under OVERWRITE is unrecoverable. Fall
     * back to the configured endpoint only when the cluster does not expose an identity.
     */
    static boolean sameCluster(SearchHttpClient sourceClient,
                               SearchHttpClient targetClient,
                               MigrationConnection source,
                               MigrationConnection target) {
        try {
            String sourceId = clusterUuid(sourceClient);
            String targetId = clusterUuid(targetClient);
            if (sourceId != null && targetId != null) {
                return sourceId.equals(targetId);
            }
        } catch (RuntimeException e) {
            // Root info may be blocked on a hardened cluster; the endpoint comparison is the
            // conservative fallback, not a license to skip the guard.
        }
        return sameEndpoint(source, target);
    }

    private static String clusterUuid(SearchHttpClient client) {
        JsonNode root = client.get("/");
        String uuid = root == null ? null : root.path("cluster_uuid").asText(null);
        return uuid == null || uuid.isBlank() ? null : uuid;
    }

    /** Same endpoint after trimming a trailing slash; host case does not identify a cluster. */
    private static boolean sameEndpoint(MigrationConnection a, MigrationConnection b) {
        if (a == null || b == null) {
            return false;
        }
        String left = normalizeEndpoint(a.getBaseUrl());
        String right = normalizeEndpoint(b.getBaseUrl());
        return !left.isEmpty() && left.equalsIgnoreCase(right);
    }

    private static String normalizeEndpoint(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
