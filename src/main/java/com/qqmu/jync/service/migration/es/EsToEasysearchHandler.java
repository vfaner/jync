package com.qqmu.jync.service.migration.es;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.service.migration.MigrationCancellation;
import com.qqmu.jync.service.migration.MigrationPrecheckResult;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Check;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Level;
import com.qqmu.jync.service.migration.MigrationProgressListener;
import com.qqmu.jync.service.migration.MigrationRunResult;
import com.qqmu.jync.service.migration.MigrationTypeHandler;

/**
 * Elasticsearch/OpenSearch → Easysearch (both speak the ES 7 REST protocol).
 *
 * <p>Precheck is read-only apart from a throwaway {@code jync-precheck-*} index that is
 * created and deleted immediately; the real run never runs during a precheck.
 */
@Component
public class EsToEasysearchHandler implements MigrationTypeHandler {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFLIGHT_PREFIX = "jync-precheck-";

    private final SearchHttpClientFactory clientFactory;
    private final EsToEasysearchEngine engine;

    public EsToEasysearchHandler(SearchHttpClientFactory clientFactory) {
        this.clientFactory = clientFactory;
        this.engine = new EsToEasysearchEngine(clientFactory);
    }

    @Override
    public MigrationType type() {
        return MigrationType.ES_TO_EASYSEARCH;
    }

    @Override
    public Set<MigrationConnectionKind> sourceKinds() {
        return Set.of(MigrationConnectionKind.ELASTICSEARCH,
                MigrationConnectionKind.OPENSEARCH);
    }

    @Override
    public Set<MigrationConnectionKind> targetKinds() {
        return Set.of(MigrationConnectionKind.EASYSEARCH);
    }

    @Override
    public String optionsFragment() {
        return "~{migration-form/options-es :: options}";
    }

    @Override
    public JsonNode defaultOptions() {
        return EsToEasysearchOptions.parse(JSON.createObjectNode()).toJson();
    }

    /** Accepts both canonical camelCase names and the form's {@code opt.*} prefix. */
    @Override
    public JsonNode parseOptions(ObjectNode formOptions) {
        ObjectNode merged = defaultOptions().deepCopy();
        if (formOptions != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = formOptions.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                if (key.startsWith("opt.")) {
                    key = key.substring("opt.".length());
                }
                if (merged.has(key)) {
                    merged.set(key, entry.getValue());
                }
            }
        }
        return EsToEasysearchOptions.parse(merged).toJson();
    }

    @Override
    public MigrationPrecheckResult precheck(MigrationTask task, JsonNode optionsNode,
                                            MigrationConnection source,
                                            MigrationConnection target) {
        EsToEasysearchOptions options = EsToEasysearchOptions.parse(optionsNode);
        List<Check> checks = new ArrayList<>();

        SearchHttpClient sourceClient;
        try {
            sourceClient = clientFactory.create(source);
        } catch (RuntimeException e) {
            return result(checks, Level.ERROR, "migration.precheck.source.unreachable",
                    describe(e));
        }

        JsonNode cat;
        try {
            cat = sourceClient.get(EsIndexSupport.CAT_INDICES);
        } catch (RuntimeException e) {
            return result(checks, Level.ERROR, "migration.precheck.source.unreachable",
                    describe(e));
        }
        checks.add(new Check(Level.OK, "migration.precheck.source.reachable", source.getName()));

        List<String> patterns = options.patterns();
        List<EsIndexSupport.IndexSummary> indices = EsIndexSupport.summarise(
                cat, patterns, EsIndexSupport.asksForSystemIndex(patterns));
        if (indices.isEmpty()) {
            checks.add(new Check(Level.WARN, "migration.precheck.discovery.empty",
                    String.join(", ", patterns)));
            return new MigrationPrecheckResult(checks);
        }
        if (indices.size() > options.getMaxIndices()) {
            checks.add(new Check(Level.WARN, "migration.precheck.discovery.capped",
                    indices.size() + " > " + options.getMaxIndices()));
            indices = indices.subList(0, options.getMaxIndices());
        }

        SearchHttpClient targetClient;
        try {
            targetClient = clientFactory.create(target);
        } catch (RuntimeException e) {
            return result(checks, Level.ERROR, "migration.precheck.target.unreachable",
                    describe(e));
        }

        String probeIndex = null;
        ObjectNode probeBody = null;

        for (EsIndexSupport.IndexSummary index : indices) {
            String targetIndex;
            try {
                targetIndex = options.targetName(index.index());
            } catch (IllegalArgumentException e) {
                checks.add(new Check(Level.ERROR, "migration.precheck.index.targetName",
                        index.index() + " -> " + e.getMessage()));
                continue;
            }

            JsonNode settings;
            JsonNode mapping;
            try {
                settings = sourceClient.get("/" + segment(index.index()) + "/_settings");
                mapping = sourceClient.get("/" + segment(index.index()) + "/_mapping");
            } catch (RuntimeException e) {
                checks.add(new Check(Level.ERROR, "migration.precheck.index.readFailed",
                        index.index() + ": " + describe(e)));
                continue;
            }

            if (EsIndexSupport.sourceDisabled(mapping, index.index())) {
                checks.add(new Check(Level.ERROR, "migration.precheck.index.noSource",
                        index.index()));
                continue;
            }

            Set<String> unsupported = EsIndexSupport.unsupportedMappingTypes(
                    mapping, index.index());
            if (!unsupported.isEmpty()) {
                checks.add(new Check(Level.WARN, "migration.precheck.index.mapping",
                        index.index() + ": " + String.join(", ", unsupported)));
            }

            if (options.getWriteMode() == EsToEasysearchOptions.WriteMode.FAIL_IF_EXISTS) {
                try {
                    if (targetClient.exists("/" + segment(targetIndex))) {
                        checks.add(new Check(Level.ERROR,
                                "migration.precheck.index.targetExists", targetIndex));
                    }
                } catch (RuntimeException e) {
                    checks.add(new Check(Level.ERROR,
                            "migration.precheck.target.unreachable", describe(e)));
                    break;
                }
            }

            if (probeIndex == null) {
                probeIndex = index.index();
                probeBody = JSON.createObjectNode();
                if (options.isCopySettings()) {
                    probeBody.set("settings",
                            EsIndexSupport.cleanSettings(settings, index.index()));
                }
                if (options.isCopyMappings()) {
                    probeBody.set("mappings", EsIndexSupport.mappingFor(mapping, index.index()));
                }
            }
        }

        if (options.isProbeCompatibility() && probeIndex != null) {
            String temp = PREFLIGHT_PREFIX + UUID.randomUUID().toString().replace("-", "");
            try {
                targetClient.putJson("/" + temp, probeBody);
                checks.add(new Check(Level.OK, "migration.precheck.compat.ok", probeIndex));
            } catch (RuntimeException e) {
                checks.add(new Check(Level.ERROR, "migration.precheck.compat.failed",
                        describe(e)));
            } finally {
                try {
                    targetClient.delete("/" + temp);
                } catch (RuntimeException ignored) {
                    // The probe failed or the cluster already removed it; precheck still stands.
                }
            }
        }

        return new MigrationPrecheckResult(checks);
    }

    @Override
    public MigrationRunResult run(MigrationTask task, JsonNode options,
                                  MigrationConnection source, MigrationConnection target,
                                  MigrationCancellation cancellation,
                                  MigrationProgressListener progress) {
        return engine.run(task, options, source, target, cancellation, progress);
    }

    private static MigrationPrecheckResult result(List<Check> checks, Level level,
                                                  String code, String detail) {
        checks.add(new Check(level, code, detail));
        return new MigrationPrecheckResult(checks);
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Discovery is exposed for the form's index list without duplicating cat parsing. */
    public List<EsIndexSupport.IndexSummary> discover(MigrationConnection source,
                                                       JsonNode optionsNode) {
        EsToEasysearchOptions options = EsToEasysearchOptions.parse(optionsNode);
        SearchHttpClient client = clientFactory.create(source);
        List<String> patterns = options.patterns();
        return EsIndexSupport.summarise(client.get(EsIndexSupport.CAT_INDICES), patterns,
                EsIndexSupport.asksForSystemIndex(patterns));
    }
}
