package com.qqmu.jync.service.migration.mongo;

import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.qqmu.jync.service.migration.MigrationGlob;
import com.qqmu.jync.service.migration.MigrationTargetNaming;
import com.qqmu.jync.service.migration.MigrationWriteMode;

import lombok.Getter;

/** Normalised options for a MongoDB → KingbaseES migration. */
@Getter
public final class MongoToKingbaseOptions {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final int DEFAULT_BATCH_SIZE = 1000;
    public static final int DEFAULT_SAMPLE_LIMIT = 200;
    public static final int DEFAULT_MAX_COLLECTIONS = 200;
    public static final String JSON_NESTED_MODE = "JSON";

    private final String collectionsText;
    private final MigrationTargetNaming targetNaming;
    private final String targetPrefix;
    private final String targetSuffix;
    private final MigrationWriteMode writeMode;
    private final String nestedMode;
    private final boolean createPrimaryKey;
    private final boolean upsertOnId;
    private final boolean continueOnError;
    private final int batchSize;
    private final int sampleLimit;
    private final boolean verifyCount;
    private final int verifySampleSize;
    private final int maxCollections;

    private MongoToKingbaseOptions(String collectionsText, MigrationTargetNaming targetNaming,
                                   String targetPrefix, String targetSuffix,
                                   MigrationWriteMode writeMode, String nestedMode,
                                   boolean createPrimaryKey, boolean upsertOnId,
                                   boolean continueOnError, int batchSize, int sampleLimit,
                                   boolean verifyCount, int verifySampleSize,
                                   int maxCollections) {
        this.collectionsText = collectionsText;
        this.targetNaming = targetNaming;
        this.targetPrefix = targetPrefix;
        this.targetSuffix = targetSuffix;
        this.writeMode = writeMode;
        this.nestedMode = nestedMode;
        this.createPrimaryKey = createPrimaryKey;
        this.upsertOnId = upsertOnId;
        this.continueOnError = continueOnError;
        this.batchSize = batchSize;
        this.sampleLimit = sampleLimit;
        this.verifyCount = verifyCount;
        this.verifySampleSize = verifySampleSize;
        this.maxCollections = maxCollections;
    }

    public static MongoToKingbaseOptions parse(JsonNode node) {
        String collectionsText = text(node, "collectionsText", "");
        MigrationTargetNaming targetNaming = enumValue(MigrationTargetNaming.class,
                text(node, "targetNaming", "SAME"),
                "error.migration.options.targetNaming.invalid");
        String targetPrefix = text(node, "targetPrefix", "");
        String targetSuffix = text(node, "targetSuffix", "");
        MigrationWriteMode writeMode = enumValue(MigrationWriteMode.class,
                text(node, "writeMode", "OVERWRITE"),
                "error.migration.options.writeMode.invalid");
        String nestedMode = text(node, "nestedMode", JSON_NESTED_MODE)
                .trim().toUpperCase(Locale.ROOT);
        if (!JSON_NESTED_MODE.equals(nestedMode)) {
            throw new IllegalArgumentException("error.migration.options.nestedMode.invalid");
        }

        boolean createPrimaryKey = bool(node, "createPrimaryKey", true);
        boolean upsertOnId = bool(node, "upsertOnId", false);
        if (upsertOnId && !createPrimaryKey) {
            throw new IllegalArgumentException("error.migration.options.upsertOnId.invalid");
        }
        boolean continueOnError = bool(node, "continueOnError", true);

        int batchSize = integer(node, "batchSize", DEFAULT_BATCH_SIZE);
        if (batchSize < 1 || batchSize > 10000) {
            throw new IllegalArgumentException("error.migration.options.batchSize.invalid");
        }
        int sampleLimit = integer(node, "sampleLimit", DEFAULT_SAMPLE_LIMIT);
        if (sampleLimit < 1 || sampleLimit > 1000) {
            throw new IllegalArgumentException("error.migration.options.sampleLimit.invalid");
        }
        boolean verifyCount = bool(node, "verifyCount", true);
        int verifySampleSize = integer(node, "verifySampleSize", 100);
        if (verifySampleSize < 0 || verifySampleSize > 1000) {
            throw new IllegalArgumentException(
                    "error.migration.options.verifySampleSize.invalid");
        }
        int maxCollections = integer(node, "maxCollections", DEFAULT_MAX_COLLECTIONS);
        if (maxCollections < 1 || maxCollections > 10000) {
            throw new IllegalArgumentException(
                    "error.migration.options.maxCollections.invalid");
        }

        return new MongoToKingbaseOptions(collectionsText, targetNaming, targetPrefix,
                targetSuffix, writeMode, nestedMode, createPrimaryKey, upsertOnId,
                continueOnError, batchSize, sampleLimit, verifyCount, verifySampleSize,
                maxCollections);
    }

    public List<String> patterns() {
        return MigrationGlob.split(collectionsText);
    }

    public ObjectNode toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("collectionsText", collectionsText);
        node.put("targetNaming", targetNaming.name());
        node.put("targetPrefix", targetPrefix);
        node.put("targetSuffix", targetSuffix);
        node.put("writeMode", writeMode.name());
        node.put("nestedMode", nestedMode);
        node.put("createPrimaryKey", createPrimaryKey);
        node.put("upsertOnId", upsertOnId);
        node.put("continueOnError", continueOnError);
        node.put("batchSize", batchSize);
        node.put("sampleLimit", sampleLimit);
        node.put("verifyCount", verifyCount);
        node.put("verifySampleSize", verifySampleSize);
        node.put("maxCollections", maxCollections);
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

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String errorKey) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(errorKey);
        }
    }
}
