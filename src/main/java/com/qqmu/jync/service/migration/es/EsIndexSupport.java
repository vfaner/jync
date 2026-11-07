package com.qqmu.jync.service.migration.es;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pure helpers shared by precheck and the migration engine: names, globs, settings cleanup. */
public final class EsIndexSupport {

    /** Discovery request shared by precheck and the engine. */
    public static final String CAT_INDICES =
            "/_cat/indices?format=json&h=index,docs.count,store.size,status,health"
                    + "&expand_wildcards=open";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Mapping types Easysearch either lacks or interprets differently; precheck warns. */
    private static final Set<String> UNSUPPORTED_TYPES = Set.of(
            "knn_vector", "dense_vector", "sparse_vector", "rank_features", "rank_feature",
            "flat_object", "completion", "search_as_you_type", "percolator", "join");

    /** Settings that describe the source cluster rather than the index's data. */
    private static final List<String> DROPPED_SETTINGS = List.of(
            "uuid",
            "creation_date",
            "provided_name",
            "version",
            "history.uuid",
            "verified_before_close",
            "lifecycle",
            "default_pipeline",
            "final_pipeline",
            "frozen",
            "hidden",
            "search.throttled",
            "blocks.read_only",
            "blocks.read_only_allow_delete",
            "blocks.write",
            "blocks.metadata",
            "routing.allocation.initial_recovery",
            "store.snapshot",
            "resize.source");

    public enum TargetNaming {
        SAME,
        PREFIX,
        SUFFIX
    }

    private EsIndexSupport() {
    }

    public static String targetName(String sourceIndex, TargetNaming naming,
                                    String prefix, String suffix) {
        String name;
        if (naming == TargetNaming.PREFIX) {
            name = (prefix == null ? "" : prefix) + sourceIndex;
        } else if (naming == TargetNaming.SUFFIX) {
            name = sourceIndex + (suffix == null ? "" : suffix);
        } else {
            name = sourceIndex;
        }
        validateTargetName(name);
        return name;
    }

    /** Rejects names Elasticsearch refuses: uppercase, leading punctuation, or too long. */
    public static void validateTargetName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255
                || !name.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("error.migration.target.name.invalid");
        }
    }

    public static boolean isSystemIndex(String index) {
        return index != null && index.startsWith(".");
    }

    public static boolean matches(String index, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isEmpty() && globMatches(index, pattern.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatches(String value, String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString()).matcher(value).matches();
    }

    /** Whether a user's pattern explicitly names a dotted system index. */
    public static boolean asksForSystemIndex(List<String> patterns) {
        if (patterns == null) {
            return false;
        }
        return patterns.stream().anyMatch(pattern -> pattern != null
                && pattern.trim().startsWith("."));
    }

    /**
     * Returns the source index's settings with cluster-specific keys removed.
     *
     * <p>Both the nested form ({@code index.uuid}) returned by modern clusters and the flat
     * dotted form used by older ones are normalised before cleanup.
     */
    public static ObjectNode cleanSettings(JsonNode settingsResponse, String index) {
        JsonNode wrapper = settingsResponse == null ? null : settingsResponse.get(index);
        JsonNode settings = wrapper == null ? null : wrapper.get("settings");
        ObjectNode normalised = normaliseSettings(settings);
        JsonNode existing = normalised.get("index");
        ObjectNode indexNode = existing instanceof ObjectNode
                ? (ObjectNode) existing : JSON.createObjectNode();
        normalised.set("index", indexNode);
        for (String path : DROPPED_SETTINGS) {
            removePath(indexNode, path);
        }
        return normalised;
    }

    private static ObjectNode normaliseSettings(JsonNode settings) {
        ObjectNode result = JSON.createObjectNode();
        if (settings == null || !settings.isObject()) {
            return result;
        }
        JsonNode nestedIndex = settings.get("index");
        ObjectNode indexNode = nestedIndex instanceof ObjectNode
                ? ((ObjectNode) nestedIndex).deepCopy() : JSON.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = settings.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (key.equals("index")) {
                continue;
            }
            if (key.startsWith("index.")) {
                putNested(indexNode, key.substring("index.".length()), entry.getValue());
            } else {
                result.set(key, entry.getValue().deepCopy());
            }
        }
        result.set("index", indexNode);
        return result;
    }

    private static void putNested(ObjectNode root, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = current.get(parts[i]);
            if (!(next instanceof ObjectNode)) {
                current.set(parts[i], JSON.createObjectNode());
            }
            current = (ObjectNode) current.get(parts[i]);
        }
        current.set(parts[parts.length - 1], value.deepCopy());
    }

    private static void removePath(ObjectNode root, String path) {
        String[] parts = path.split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = current.get(parts[i]);
            if (!(next instanceof ObjectNode)) {
                // Nothing nested to remove; a flat dotted key would have been normalised.
                return;
            }
            current = (ObjectNode) next;
        }
        current.remove(parts[parts.length - 1]);
    }

    /** True when the source index deliberately stores no {@code _source}. */
    public static boolean sourceDisabled(JsonNode mappingResponse, String index) {
        JsonNode source = mappingNode(mappingResponse, index).path("_source");
        if (source.isBoolean()) {
            return !source.asBoolean(true);
        }
        if (source.isObject()) {
            return source.has("enabled") && !source.path("enabled").asBoolean(true);
        }
        return false;
    }

    /** Mapping types outside the ES 7 core subset, plus runtime fields. */
    public static Set<String> unsupportedMappingTypes(JsonNode mappingResponse, String index) {
        JsonNode mapping = mappingNode(mappingResponse, index);
        Set<String> unsupported = new TreeSet<>();
        collectTypes(mapping.path("properties"), unsupported);
        if (mapping.has("runtime") && mapping.path("runtime").size() > 0) {
            unsupported.add("runtime");
        }
        return unsupported;
    }

    private static void collectTypes(JsonNode properties, Set<String> found) {
        if (properties == null || !properties.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = properties.properties().iterator();
        while (fields.hasNext()) {
            JsonNode field = fields.next().getValue();
            String type = field.path("type").asString("").toLowerCase(Locale.ROOT);
            if (UNSUPPORTED_TYPES.contains(type)) {
                found.add(type);
            }
            collectTypes(field.path("properties"), found);
            JsonNode fieldsNode = field.path("fields");
            if (fieldsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> sub = fieldsNode.properties().iterator();
                while (sub.hasNext()) {
                    String subType = sub.next().getValue().path("type").asString("")
                            .toLowerCase(Locale.ROOT);
                    if (UNSUPPORTED_TYPES.contains(subType)) {
                        found.add(subType);
                    }
                }
            }
        }
    }

    /** The {@code mappings} object of one index, ready to PUT on the target. */
    public static ObjectNode mappingFor(JsonNode mappingResponse, String index) {
        JsonNode mapping = mappingNode(mappingResponse, index);
        return mapping instanceof ObjectNode ? ((ObjectNode) mapping).deepCopy()
                : JSON.createObjectNode();
    }

    private static JsonNode mappingNode(JsonNode mappingResponse, String index) {
        JsonNode wrapper = mappingResponse == null ? null : mappingResponse.get(index);
        return wrapper == null ? JSON.createObjectNode() : wrapper.path("mappings");
    }

    /** Discovery rows in source order, preserving only the fields the UI shows. */
    public static List<IndexSummary> summarise(JsonNode catResponse, List<String> patterns,
                                               boolean includeSystemWhenAsked) {
        List<IndexSummary> rows = new ArrayList<>();
        if (catResponse == null || !catResponse.isArray()) {
            return rows;
        }
        for (JsonNode row : catResponse) {
            String name = row.path("index").asString("");
            if (name.isBlank()) {
                continue;
            }
            if (isSystemIndex(name) && !includeSystemWhenAsked) {
                continue;
            }
            if (!matches(name, patterns)) {
                continue;
            }
            rows.add(new IndexSummary(name, row.path("docs.count").asLong(0),
                    row.path("store.size").asString(""), row.path("status").asString(""),
                    row.path("health").asString("")));
        }
        return rows;
    }

    /** One {@code _cat/indices} row. */
    public record IndexSummary(String index, long docsCount, String storeSize,
                               String status, String health) {
    }

}
