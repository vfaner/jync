package com.qqmu.jync.service.migration.es;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
                        targetIndex, cancellation, progressCounters, progress));
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
                                     MigrationCancellation cancellation,
                                     Progress progressCounters,
                                     MigrationProgressListener progress) {
        String sourcePath = "/" + segment(sourceIndex);
        String targetPath = "/" + segment(targetIndex);
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
        Iterator<Map.Entry<String, JsonNode>> iterator = aliases.fields();
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
        query.put("size", options.getBatchSize());
        ArrayNode sort = query.putArray("sort");
        sort.add("_doc");
        query.put("_source", true);
        query.putObject("query").putObject("match_all");

        String searchPath = sourcePath + "/_search?scroll=" + options.getScrollTtl();
        JsonNode page = sourceClient.search(searchPath, query);
        String scrollId = page == null ? null : page.path("_scroll_id").asText(null);
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
                    String id = hit.path("_id").asText(null);
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
                        : next.path("_scroll_id").asText(scrollId);
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

    /** Returns how many sampled documents have a target {@code _source} unlike the source's. */
    private static int verifySample(EsToEasysearchOptions options,
                                    SearchHttpClient sourceClient,
                                    SearchHttpClient targetClient,
                                    String sourcePath, String targetPath) {
        ObjectNode query = JSON.createObjectNode();
        query.put("size", options.getVerifySampleSize());
        query.put("_source", true);
        query.putObject("query").putObject("match_all");

        JsonNode sample = sourceClient.search(
                sourcePath + "/_search?size=" + options.getVerifySampleSize(), query);
        JsonNode hits = sample == null ? null : sample.path("hits").path("hits");
        if (hits == null || !hits.isArray() || hits.isEmpty()) {
            return 0;
        }

        Map<String, JsonNode> expected = new LinkedHashMap<>();
        ArrayNode ids = JSON.createArrayNode();
        for (JsonNode hit : hits) {
            String id = hit.path("_id").asText("");
            if (id.isEmpty()) {
                continue;
            }
            ids.add(id);
            expected.put(id, hit.get("_source"));
        }
        if (ids.isEmpty()) {
            return 0;
        }

        ObjectNode body = JSON.createObjectNode();
        body.set("ids", ids);
        JsonNode response = targetClient.postJson(targetPath + "/_mget", body);
        JsonNode docs = response == null ? null : response.path("docs");
        if (docs == null || !docs.isArray()) {
            return expected.size();
        }

        int mismatches = 0;
        for (JsonNode doc : docs) {
            String id = doc.path("_id").asText("");
            JsonNode source = expected.get(id);
            if (source == null) {
                continue;
            }
            if (!doc.path("found").asBoolean(false)
                    || !source.equals(doc.get("_source"))) {
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
        } catch (IOException e) {
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
}
