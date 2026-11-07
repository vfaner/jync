package com.qqmu.jync.service.migration.es;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import lombok.Getter;

/**
 * Normalised options for one ES/OpenSearch → Easysearch migration.
 *
 * <p>Parsing is deliberately strict about values it understands and silent about fields it does
 * not: an older task stored by a newer version must still load, while a typo in an enum must not
 * silently fall back to a destructive default.
 */
@Getter
public final class EsToEasysearchOptions {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final int DEFAULT_BATCH_SIZE = 1000;
    public static final long DEFAULT_MAX_BULK_BYTES = 10L * 1024 * 1024;
    public static final String DEFAULT_SCROLL_TTL = "5m";
    public static final int DEFAULT_MAX_INDICES = 200;

    public enum TargetNaming {
        SAME,
        PREFIX,
        SUFFIX
    }

    public enum WriteMode {
        OVERWRITE,
        APPEND,
        FAIL_IF_EXISTS
    }

    private final String indicesText;
    private final TargetNaming targetNaming;
    private final String targetPrefix;
    private final String targetSuffix;
    private final WriteMode writeMode;
    private final boolean copySettings;
    private final boolean copyMappings;
    private final boolean copyAliases;
    private final boolean continueOnError;
    private final int batchSize;
    private final long maxBulkBytes;
    private final String scrollTtl;
    private final boolean verifyCount;
    private final int verifySampleSize;
    private final boolean probeCompatibility;
    private final int maxIndices;

    private EsToEasysearchOptions(String indicesText, TargetNaming targetNaming,
                                  String targetPrefix, String targetSuffix, WriteMode writeMode,
                                  boolean copySettings, boolean copyMappings,
                                  boolean copyAliases, boolean continueOnError, int batchSize,
                                  long maxBulkBytes, String scrollTtl, boolean verifyCount,
                                  int verifySampleSize, boolean probeCompatibility,
                                  int maxIndices) {
        this.indicesText = indicesText;
        this.targetNaming = targetNaming;
        this.targetPrefix = targetPrefix;
        this.targetSuffix = targetSuffix;
        this.writeMode = writeMode;
        this.copySettings = copySettings;
        this.copyMappings = copyMappings;
        this.copyAliases = copyAliases;
        this.continueOnError = continueOnError;
        this.batchSize = batchSize;
        this.maxBulkBytes = maxBulkBytes;
        this.scrollTtl = scrollTtl;
        this.verifyCount = verifyCount;
        this.verifySampleSize = verifySampleSize;
        this.probeCompatibility = probeCompatibility;
        this.maxIndices = maxIndices;
    }

    /** Reads and validates stored JSON. Unknown fields are ignored. */
    public static EsToEasysearchOptions parse(JsonNode node) {
        String indicesText = text(node, "indicesText", "");
        TargetNaming targetNaming = enumValue(TargetNaming.class,
                text(node, "targetNaming", "SAME"), "error.migration.options.targetNaming.invalid");
        String targetPrefix = text(node, "targetPrefix", "");
        String targetSuffix = text(node, "targetSuffix", "_migrated");
        WriteMode writeMode = enumValue(WriteMode.class,
                text(node, "writeMode", "OVERWRITE"), "error.migration.options.writeMode.invalid");

        boolean copySettings = bool(node, "copySettings", true);
        boolean copyMappings = bool(node, "copyMappings", true);
        boolean copyAliases = bool(node, "copyAliases", false);
        boolean continueOnError = bool(node, "continueOnError", true);

        int batchSize = integer(node, "batchSize", DEFAULT_BATCH_SIZE);
        if (batchSize < 1 || batchSize > 10000) {
            throw new IllegalArgumentException("error.migration.options.batchSize.invalid");
        }
        long maxBulkBytes = longValue(node, "maxBulkBytes", DEFAULT_MAX_BULK_BYTES);
        if (maxBulkBytes < 1024 * 1024 || maxBulkBytes > 100L * 1024 * 1024) {
            throw new IllegalArgumentException("error.migration.options.maxBulkBytes.invalid");
        }
        String scrollTtl = text(node, "scrollTtl", DEFAULT_SCROLL_TTL);
        if (!scrollTtl.matches("\\d+[smh]")) {
            throw new IllegalArgumentException("error.migration.options.scrollTtl.invalid");
        }
        boolean verifyCount = bool(node, "verifyCount", true);
        int verifySampleSize = integer(node, "verifySampleSize", 100);
        if (verifySampleSize < 0 || verifySampleSize > 1000) {
            throw new IllegalArgumentException(
                    "error.migration.options.verifySampleSize.invalid");
        }
        boolean probeCompatibility = bool(node, "probeCompatibility", true);
        int maxIndices = integer(node, "maxIndices", DEFAULT_MAX_INDICES);
        if (maxIndices < 1 || maxIndices > 1000) {
            throw new IllegalArgumentException("error.migration.options.maxIndices.invalid");
        }

        return new EsToEasysearchOptions(indicesText, targetNaming, targetPrefix, targetSuffix,
                writeMode, copySettings, copyMappings, copyAliases, continueOnError, batchSize,
                maxBulkBytes, scrollTtl, verifyCount, verifySampleSize, probeCompatibility,
                maxIndices);
    }

    /** The patterns a user typed, comma/newline separated; empty means every non-system index. */
    public List<String> patterns() {
        List<String> patterns = new ArrayList<>();
        for (String part : indicesText.split("[,\\r\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                patterns.add(trimmed);
            }
        }
        return patterns;
    }

    /** Source index name to target index name, validated against Elasticsearch's rules. */
    public String targetName(String sourceIndex) {
        EsIndexSupport.TargetNaming naming = EsIndexSupport.TargetNaming.valueOf(
                targetNaming.name());
        return EsIndexSupport.targetName(sourceIndex, naming, targetPrefix, targetSuffix);
    }

    public ObjectNode toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("indicesText", indicesText);
        node.put("targetNaming", targetNaming.name());
        node.put("targetPrefix", targetPrefix);
        node.put("targetSuffix", targetSuffix);
        node.put("writeMode", writeMode.name());
        node.put("copySettings", copySettings);
        node.put("copyMappings", copyMappings);
        node.put("copyAliases", copyAliases);
        node.put("continueOnError", continueOnError);
        node.put("batchSize", batchSize);
        node.put("maxBulkBytes", maxBulkBytes);
        node.put("scrollTtl", scrollTtl);
        node.put("verifyCount", verifyCount);
        node.put("verifySampleSize", verifySampleSize);
        node.put("probeCompatibility", probeCompatibility);
        node.put("maxIndices", maxIndices);
        return node;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asString();
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asBoolean(fallback);
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asInt(fallback);
    }

    private static long longValue(JsonNode node, String field, long fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asLong(fallback);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String errorKey) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(errorKey);
        }
    }
}
