package com.qqmu.jync.service.migration.mongo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;

import tools.jackson.databind.JsonNode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.service.migration.MigrationCancellation;
import com.qqmu.jync.service.migration.MigrationCancelledException;
import com.qqmu.jync.service.migration.MigrationProgressListener;
import com.qqmu.jync.service.migration.MigrationRunResult;
import com.qqmu.jync.service.migration.MigrationRunResult.IndexResult;
import com.qqmu.jync.service.migration.MigrationRunResult.Status;
import com.qqmu.jync.service.migration.MigrationWriteMode;

/**
 * Copies collections from one MongoDB database to another.
 *
 * <p>Each collection is independent: nothing is rolled back, a failure does not stop later
 * collections unless {@code continueOnError} is off, and a cancelled or failed collection keeps
 * its partial counters in the run history.
 */
final class MongoToMongoEngine {

    private final MongoMigrationGatewayFactory gatewayFactory;

    MongoToMongoEngine(MongoMigrationGatewayFactory gatewayFactory) {
        this.gatewayFactory = gatewayFactory;
    }

    MigrationRunResult run(MigrationTask task, JsonNode optionsNode,
                           MigrationConnection source, MigrationConnection target,
                           MigrationCancellation cancellation,
                           MigrationProgressListener progress) {
        MongoToMongoOptions options;
        try {
            options = MongoToMongoOptions.parse(optionsNode);
        } catch (RuntimeException e) {
            return failedRun("invalid options: " + describe(e));
        }
        if (MongoConnectionIdentity.sameDatabase(source, target)) {
            // OVERWRITE drops the target collection first; if the two connections point at the
            // same database, that would delete the source before it is read.
            return failedRun("error.migration.mongo.sameDatabase");
        }

        MongoMigrationGateway sourceGateway = null;
        MongoMigrationGateway targetGateway = null;
        try {
            sourceGateway = gatewayFactory.open(source);
            targetGateway = gatewayFactory.open(target);
            // The URI spelling above only catches identical connection strings. Ask the servers
            // too: `localhost`/`127.0.0.1` and a single host vs a seed list reach the same data,
            // and OVERWRITE would drop the source along with the target.
            MongoServerIdentity sourceIdentity = sourceGateway.serverIdentity();
            if (sourceIdentity != null
                    && sourceIdentity.sameDatabaseAs(targetGateway.serverIdentity())) {
                return failedRun("error.migration.mongo.sameDatabase");
            }
            return runWithGateways(options, sourceGateway, targetGateway, cancellation, progress);
        } catch (RuntimeException e) {
            return failedRun(describe(e));
        } finally {
            closeQuietly(targetGateway);
            closeQuietly(sourceGateway);
        }
    }

    private MigrationRunResult runWithGateways(MongoToMongoOptions options,
                                               MongoMigrationGateway sourceGateway,
                                               MongoMigrationGateway targetGateway,
                                               MigrationCancellation cancellation,
                                               MigrationProgressListener progress) {
        List<MongoCollectionInfo> discovered;
        try {
            discovered = sourceGateway.listCollections();
        } catch (RuntimeException e) {
            return failedRun("collection discovery failed: " + describe(e));
        }

        List<String> runWarnings = new ArrayList<>();
        List<MongoCollectionInfo> collections =
                MongoMigrationSupport.matching(discovered, options.getCollectionsText());
        if (collections.isEmpty()) {
            runWarnings.add("WARN: no collections matched "
                    + String.join(", ", options.patterns()));
        } else if (collections.size() > options.getMaxCollections()) {
            runWarnings.add("WARN: " + collections.size() + " collections matched, only the first "
                    + options.getMaxCollections() + " will be migrated");
            collections = collections.subList(0, options.getMaxCollections());
        }

        List<IndexResult> results = new ArrayList<>();
        Set<String> targetNames = new HashSet<>();
        boolean cancelled = false;
        for (MongoCollectionInfo info : collections) {
            String targetName;
            try {
                targetName = options.targetName(info.name());
            } catch (IllegalArgumentException e) {
                results.add(new IndexResult(info.name(), info.name(), Status.FAILED, 0, 0,
                        e.getMessage()));
                if (!options.isContinueOnError()) {
                    break;
                }
                continue;
            }
            if (!targetNames.add(targetName)) {
                results.add(new IndexResult(info.name(), targetName, Status.FAILED, 0, 0,
                        "target collection name is already used by another source collection"));
                if (!options.isContinueOnError()) {
                    break;
                }
                continue;
            }
            if (!info.isCollection()) {
                results.add(new IndexResult(info.name(), targetName, Status.SKIPPED, 0, 0,
                        "not a collection (" + info.type() + ")"));
                continue;
            }

            MongoMigrationSupport.Progress counters = new MongoMigrationSupport.Progress();
            try {
                cancellation.check();
                results.add(migrateCollection(options, sourceGateway, targetGateway, info.name(),
                        targetName, cancellation, counters, progress));
            } catch (MigrationCancelledException e) {
                results.add(new IndexResult(info.name(), targetName, Status.CANCELLED,
                        counters.read, counters.written, "cancelled"));
                cancelled = true;
                break;
            } catch (RuntimeException e) {
                results.add(new IndexResult(info.name(), targetName, Status.FAILED,
                        counters.read, counters.written, describe(e)));
                if (!options.isContinueOnError()) {
                    break;
                }
            }
        }

        return MongoMigrationSupport.outcome(results, runWarnings, cancelled);
    }

    private IndexResult migrateCollection(MongoToMongoOptions options,
                                          MongoMigrationGateway sourceGateway,
                                          MongoMigrationGateway targetGateway,
                                          String sourceName, String targetName,
                                          MigrationCancellation cancellation,
                                          MongoMigrationSupport.Progress counters,
                                          MigrationProgressListener progress) {
        long existingTargetCount = 0;
        boolean exists = targetGateway.collectionExists(targetName);
        if (exists) {
            if (options.getWriteMode() == MigrationWriteMode.FAIL_IF_EXISTS) {
                throw new IllegalStateException(
                        "target collection already exists: " + targetName);
            }
            if (options.getWriteMode() == MigrationWriteMode.OVERWRITE) {
                targetGateway.dropCollection(targetName);
                exists = false;
            } else {
                existingTargetCount = targetGateway.countDocuments(targetName);
            }
        }

        Document sourceOptions = sourceGateway.collectionOptions(sourceName);
        if (!exists) {
            targetGateway.createCollection(targetName,
                    options.isCopyCollectionOptions() ? sourceOptions : new Document());
        }
        boolean ordered = Boolean.TRUE.equals(sourceOptions.getBoolean("capped", false));

        List<String> indexWarnings = new ArrayList<>();
        if (options.isCopyIndexes()) {
            copyIndexes(options, sourceGateway, targetGateway, sourceName, targetName,
                    indexWarnings);
        }
        List<String> warnings = new ArrayList<>();
        if (options.isCopyDocuments()) {
            copyDocuments(options, sourceGateway, targetGateway, sourceName, targetName, ordered,
                    cancellation, counters, progress, warnings);
        }
        // A missing index does not justify throwing away the documents that were just copied:
        // finish the collection, then report it as failed.
        if (!indexWarnings.isEmpty()) {
            throw new IllegalStateException(
                    "indexes could not be created: " + String.join("; ", indexWarnings));
        }

        if (options.isCopyDocuments() && options.isVerifyCount()
                && !verifyCount(options, sourceGateway, targetGateway, sourceName, targetName,
                        existingTargetCount, counters)) {
            throw new IllegalStateException(
                    "document count mismatch: source and target differ");
        }

        if (options.isCopyDocuments() && options.getVerifySampleSize() > 0) {
            int mismatches = verifySample(options, sourceGateway, targetGateway, sourceName,
                    targetName);
            if (mismatches > 0) {
                warnings.add("WARN: " + mismatches
                        + " sampled documents differ between source and target");
            }
        }

        return new IndexResult(sourceName, targetName, Status.SUCCESS, counters.read,
                counters.written, warnings.isEmpty() ? null : String.join("; ", warnings));
    }

    private static void copyIndexes(MongoToMongoOptions options,
                                    MongoMigrationGateway sourceGateway,
                                    MongoMigrationGateway targetGateway,
                                    String sourceName, String targetName,
                                    List<String> warnings) {
        List<Document> indexes;
        try {
            indexes = sourceGateway.listIndexes(sourceName);
        } catch (RuntimeException e) {
            warnings.add("listIndexes: " + describe(e));
            return;
        }
        for (Document index : indexes) {
            if (MongoCollectionSupport.isIdIndex(index)) {
                continue;
            }
            try {
                targetGateway.createIndex(targetName, index);
            } catch (RuntimeException e) {
                // A conflicting index must not hide the documents that will still be copied.
                warnings.add(index.getString("name") + ": " + describe(e));
            }
        }
    }

    private static void copyDocuments(MongoToMongoOptions options,
                                      MongoMigrationGateway sourceGateway,
                                      MongoMigrationGateway targetGateway,
                                      String sourceName, String targetName, boolean ordered,
                                      MigrationCancellation cancellation,
                                      MongoMigrationSupport.Progress counters,
                                      MigrationProgressListener progress,
                                      List<String> warnings) {
        try (MongoDocumentCursor cursor = sourceGateway.find(sourceName,
                options.getBatchSize(), options.isNoCursorTimeout())) {
            List<Document> batch = new ArrayList<>();
            long batchBytes = 0;
            while (cursor.hasNext()) {
                Document document = cursor.next();
                long size = estimateBytes(document);
                if (!batch.isEmpty() && (batch.size() >= options.getBatchSize()
                        || batchBytes + size > options.getMaxBatchBytes())) {
                    flush(options, targetGateway, targetName, batch, ordered, counters, warnings);
                    batch = new ArrayList<>();
                    batchBytes = 0;
                    cancellation.check();
                    progress.onProgress(sourceName, counters.read, counters.written);
                }
                batch.add(document);
                batchBytes += size;
                counters.read++;
            }
            if (!batch.isEmpty()) {
                flush(options, targetGateway, targetName, batch, ordered, counters, warnings);
                progress.onProgress(sourceName, counters.read, counters.written);
            }
        }
    }

    private static void flush(MongoToMongoOptions options, MongoMigrationGateway targetGateway,
                              String targetName, List<Document> batch, boolean ordered,
                              MongoMigrationSupport.Progress counters, List<String> warnings) {
        BulkInsertResult result = targetGateway.insertMany(targetName, batch, ordered);
        if (result.duplicates() > 0) {
            if (options.getWriteMode() != MigrationWriteMode.APPEND) {
                throw new IllegalStateException(
                        result.duplicates() + " documents were rejected as duplicate _id");
            }
            warnings.add("WARN: " + result.duplicates()
                    + " documents already existed and were not overwritten");
        }
        counters.written += result.inserted();
    }

    private static boolean verifyCount(MongoToMongoOptions options,
                                       MongoMigrationGateway sourceGateway,
                                       MongoMigrationGateway targetGateway,
                                       String sourceName, String targetName,
                                       long existingTargetCount, MongoMigrationSupport.Progress counters) {
        long targetCount = targetGateway.countDocuments(targetName);
        if (options.getWriteMode() == MigrationWriteMode.APPEND) {
            return targetCount >= existingTargetCount + counters.written;
        }
        return sourceGateway.countDocuments(sourceName) == targetCount;
    }

    /** Returns how many sampled documents differ from the target copy with the same {@code _id}. */
    private static int verifySample(MongoToMongoOptions options,
                                    MongoMigrationGateway sourceGateway,
                                    MongoMigrationGateway targetGateway,
                                    String sourceName, String targetName) {
        List<Document> sample = sourceGateway.sample(sourceName, options.getVerifySampleSize());
        List<Object> ids = new ArrayList<>();
        for (Document document : sample) {
            Object id = document.get("_id");
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        Map<Object, Document> targetDocuments = new HashMap<>();
        for (Document document : targetGateway.findByIds(targetName, ids)) {
            targetDocuments.put(document.get("_id"), document);
        }
        int mismatches = 0;
        for (Document document : sample) {
            Object id = document.get("_id");
            if (id != null && !document.equals(targetDocuments.get(id))) {
                mismatches++;
            }
        }
        return mismatches;
    }

    /**
     * The batch cap only has to be conservative: UTF-8 JSON is a close upper bound for BSON
     * here, except for binary values, which JSON inflates rather than shrinks. The 8 MB
     * default leaves ample room below the 48 MB command limit.
     */
    private static long estimateBytes(Document document) {
        return document.toJson().getBytes(StandardCharsets.UTF_8).length;
    }

    private static void closeQuietly(MongoMigrationGateway gateway) {
        MongoMigrationSupport.closeQuietly(gateway);
    }

    private static MigrationRunResult failedRun(String message) {
        return MongoMigrationSupport.failedRun(message);
    }

    private static String describe(Throwable e) {
        return MongoMigrationSupport.describe(e);
    }
}
