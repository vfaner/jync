package com.qqmu.jync.service.migration.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.service.migration.MigrationGlob;
import com.qqmu.jync.service.migration.es.SearchHttpClient;
import com.qqmu.jync.service.migration.es.SearchHttpClientFactory;

/** Elasticsearch, OpenSearch and Easysearch share one REST protocol and one adapter. */
@Component
public class SearchConnectionAdapter implements MigrationConnectionKindAdapter {

    private final SearchHttpClientFactory clientFactory;

    public SearchConnectionAdapter(SearchHttpClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public Set<MigrationConnectionKind> kinds() {
        return Set.of(MigrationConnectionKind.ELASTICSEARCH,
                MigrationConnectionKind.OPENSEARCH,
                MigrationConnectionKind.EASYSEARCH);
    }

    @Override
    public MigrationConnectionEndpoint normalize(MigrationConnectionForm form) {
        // A search endpoint uses none of the relational/Mongo fields; dropping them here keeps
        // a stray form value from being persisted on an ES row.
        return new MigrationConnectionEndpoint(normalizeUrl(form.getBaseUrl()), null, null, null);
    }

    @Override
    public void validateAuth(MigrationConnectionForm form, String rawPassword, String rawApiKey,
                             MigrationConnection existing) {
        if (form.getAuthType() == MigrationConnectionAuthType.BASIC
                && (form.getUsername() == null || form.getUsername().isBlank())) {
            throw new IllegalArgumentException("error.migration.connection.username.required");
        }
        if (form.getAuthType() == MigrationConnectionAuthType.API_KEY
                && (rawApiKey == null || rawApiKey.isBlank())
                && (existing == null || existing.getApiKeyEnc() == null)) {
            throw new IllegalArgumentException("error.migration.connection.apiKey.required");
        }
    }

    @Override
    public MigrationConnectionTestResult test(MigrationConnection connection) {
        return probe(clientFactory.create(connection));
    }

    @Override
    public MigrationConnectionTestResult testTransient(MigrationConnection connection,
                                                       String plainPassword, String plainApiKey) {
        return probe(new SearchHttpClient(connection, plainPassword, plainApiKey));
    }

    private static MigrationConnectionTestResult probe(SearchHttpClient client) {
        long start = System.nanoTime();
        try {
            JsonNode root = client.get("/");
            String product = root.path("version").path("distribution").asString("");
            if (product.isBlank()) {
                product = root.path("tagline").asString("unknown");
            }
            String version = root.path("version").path("number").asString(null);
            String cluster = root.path("cluster_name").asString(null);
            return new MigrationConnectionTestResult(true, null, product, version, cluster,
                    elapsedMs(start));
        } catch (RuntimeException e) {
            return new MigrationConnectionTestResult(false, e.getMessage(), null, null, null,
                    elapsedMs(start));
        }
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** Non-system indices only, unless the user's own pattern explicitly asks for one. */
    @Override
    public List<MigrationDiscoveryItem> list(MigrationConnection connection, String pattern) {
        SearchHttpClient client = clientFactory.create(connection);
        String effective = pattern == null ? "" : pattern.trim();
        JsonNode cat = client.get("/_cat/indices?format=json"
                + "&h=index,docs.count,store.size,status,health&expand_wildcards=open");
        List<MigrationDiscoveryItem> indices = new ArrayList<>();
        if (cat != null && cat.isArray()) {
            for (JsonNode row : cat) {
                String name = row.path("index").asString("");
                if (name.isBlank()
                        || name.startsWith(".")
                        && !MigrationGlob.namesPrefix(effective, ".")) {
                    continue;
                }
                if (!MigrationGlob.matchesAny(name, effective)) {
                    continue;
                }
                indices.add(new MigrationDiscoveryItem(name, row.path("docs.count").asLong(0),
                        row.path("store.size").asString(""),
                        row.path("status").asString(""),
                        row.path("health").asString("")));
            }
        }
        return indices;
    }

    private static String normalizeUrl(String raw) {
        try {
            return SearchHttpClient.normalizeBaseUrl(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("error.migration.connection.url.invalid");
        }
    }
}
