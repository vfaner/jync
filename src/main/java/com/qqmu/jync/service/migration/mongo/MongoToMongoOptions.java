package com.qqmu.jync.service.migration.mongo;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.qqmu.jync.service.migration.MigrationGlob;
import com.qqmu.jync.service.migration.MigrationTargetNaming;
import com.qqmu.jync.service.migration.MigrationWriteMode;

import lombok.Getter;

/** Normalised options for a MongoDB → MongoDB migration. */
@Getter
public final class MongoToMongoOptions {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final int DEFAULT_BATCH_SIZE = 1000;
    public static final long DEFAULT_MAX_BATCH_BYTES = 8L * 1024 * 1024;
    public static final int DEFAULT_MAX_COLLECTIONS = 200;
    private static final int MAX_NAME_BYTES = 120;

    private final String collectionsText;
    private final MigrationTargetNaming targetNaming;
    private final String targetPrefix;
    private final String targetSuffix;
    private final MigrationWriteMode writeMode;
    private final boolean copyCollectionOptions;
    private final boolean copyIndexes;
    private final boolean copyDocuments;
    private final boolean continueOnError;
    private final int batchSize;
    private final long maxBatchBytes;
    private final boolean noCursorTimeout;
    private final boolean verifyCount;
    private final int verifySampleSize;
    private final int maxCollections;

    private MongoToMongoOptions(String collectionsText, MigrationTargetNaming targetNaming,
                                String targetPrefix, String targetSuffix,
                                MigrationWriteMode writeMode, boolean copyCollectionOptions,
                                boolean copyIndexes, boolean copyDocuments,
                                boolean continueOnError, int batchSize, long maxBatchBytes,
                                boolean noCursorTimeout, boolean verifyCount,
                                int verifySampleSize, int maxCollections) {
        this.collectionsText = collectionsText;
        this.targetNaming = targetNaming;
        this.targetPrefix = targetPrefix;
        this.targetSuffix = targetSuffix;
        this.writeMode = writeMode;
        this.copyCollectionOptions = copyCollectionOptions;
        this.copyIndexes = copyIndexes;
        this.copyDocuments = copyDocuments;
        this.continueOnError = continueOnError;
        this.batchSize = batchSize;
        this.maxBatchBytes = maxBatchBytes;
        this.noCursorTimeout = noCursorTimeout;
        this.verifyCount = verifyCount;
        this.verifySampleSize = verifySampleSize;
        this.maxCollections = maxCollections;
    }

    public static MongoToMongoOptions parse(JsonNode node) {
        String collectionsText = text(node, "collectionsText", "");
        MigrationTargetNaming targetNaming = enumValue(MigrationTargetNaming.class,
                text(node, "targetNaming", "SAME"),
                "error.migration.options.targetNaming.invalid");
        String targetPrefix = text(node, "targetPrefix", "");
        String targetSuffix = text(node, "targetSuffix", "_migrated");
        MigrationWriteMode writeMode = enumValue(MigrationWriteMode.class,
                text(node, "writeMode", "OVERWRITE"),
                "error.migration.options.writeMode.invalid");

        boolean copyCollectionOptions = bool(node, "copyCollectionOptions", true);
        boolean copyIndexes = bool(node, "copyIndexes", true);
        boolean copyDocuments = bool(node, "copyDocuments", true);
        boolean continueOnError = bool(node, "continueOnError", true);

        int batchSize = integer(node, "batchSize", DEFAULT_BATCH_SIZE);
        if (batchSize < 1 || batchSize > 10000) {
            throw new IllegalArgumentException("error.migration.options.batchSize.invalid");
        }
        long maxBatchBytes = longValue(node, "maxBatchBytes", DEFAULT_MAX_BATCH_BYTES);
        if (maxBatchBytes < 1024 * 1024 || maxBatchBytes > 64L * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "error.migration.options.maxBatchBytes.invalid");
        }
        boolean noCursorTimeout = bool(node, "noCursorTimeout", true);
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

        return new MongoToMongoOptions(collectionsText, targetNaming, targetPrefix, targetSuffix,
                writeMode, copyCollectionOptions, copyIndexes, copyDocuments, continueOnError,
                batchSize, maxBatchBytes, noCursorTimeout, verifyCount, verifySampleSize,
                maxCollections);
    }

    public List<String> patterns() {
        return MigrationGlob.split(collectionsText);
    }

    /** Source collection name to target name, validated before anything is written. */
    public String targetName(String sourceName) {
        String name;
        switch (targetNaming) {
            case PREFIX -> name = targetPrefix + sourceName;
            case SUFFIX -> name = sourceName + targetSuffix;
            default -> name = sourceName;
        }
        if (name.isBlank()
                || name.indexOf('$') >= 0
                || name.indexOf('\u0000') >= 0
                || MongoCollectionSupport.isSystem(name)
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("error.migration.options.targetName.invalid");
        }
        return name;
    }

    public ObjectNode toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("collectionsText", collectionsText);
        node.put("targetNaming", targetNaming.name());
        node.put("targetPrefix", targetPrefix);
        node.put("targetSuffix", targetSuffix);
        node.put("writeMode", writeMode.name());
        node.put("copyCollectionOptions", copyCollectionOptions);
        node.put("copyIndexes", copyIndexes);
        node.put("copyDocuments", copyDocuments);
        node.put("continueOnError", continueOnError);
        node.put("batchSize", batchSize);
        node.put("maxBatchBytes", maxBatchBytes);
        node.put("noCursorTimeout", noCursorTimeout);
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
