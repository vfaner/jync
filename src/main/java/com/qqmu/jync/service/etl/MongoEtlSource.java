package com.qqmu.jync.service.etl;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.etl.mongo.MongoCursorHandle;
import com.qqmu.jync.service.etl.mongo.MongoFilterException;
import com.qqmu.jync.service.etl.mongo.MongoFilterParser;
import com.qqmu.jync.service.etl.mongo.MongoResumeKeyCodec;
import com.qqmu.jync.service.etl.mongo.MongoSchemaException;
import com.qqmu.jync.service.etl.mongo.MongoSchemaInferrer;
import com.qqmu.jync.service.etl.mongo.MongoSourceGateway;

/**
 * A MongoDB collection seen as an ETL source through the native driver.
 *
 * <p>The target columns come from a bounded sample taken with the task's own filter, so the
 * precheck and the run agree. User configuration defects — an unparseable filter, a name the
 * target cannot reproduce, a resume key outside the sample — are program errors raised before
 * the gateway is asked for rows, which is before anything on the target is deleted. Every
 * driver failure is a source-side failure, never the target's.
 */
public final class MongoEtlSource implements EtlSource {

    static final int SAMPLE_LIMIT = 200;
    static final int BATCH_SIZE = 1000;

    private final MongoSourceGateway gateway;
    private final DatabaseConfig config;

    public MongoEtlSource(MongoSourceGateway gateway, DatabaseConfig config) {
        this.gateway = gateway;
        this.config = config;
    }

    @Override
    public EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) {
        String collection = collection(task);
        Document filter = filter(task);
        int timeout = timeout(task);
        boolean resumable = resumable(task);

        try {
            if (!gateway.collectionExists(config, collection)) {
                throw new EtlSourceException("error.etl.mongo.collection.missing", collection);
            }
            List<Document> samples = gateway.sample(
                    config, collection, filter, SAMPLE_LIMIT, timeout);
            List<EtlSourceColumn> columns = infer(samples);
            // Resolve the key before the target table is touched: a typo must not cost the
            // operator the previous contents.
            int keyIndex = resumeKeyIndex(columns, task, resumable);

            Document query = filter;
            Document sort = null;
            if (resumable) {
                String key = task.getResumeKeyColumn().trim();
                sort = new Document(key, 1);
                if (checkpoint != null) {
                    Object resumeValue = MongoResumeKeyCodec.decode(
                            checkpoint.value(), checkpoint.jdbcType());
                    if (resumeValue != null) {
                        query = MongoFilterParser.withResume(filter, key, resumeValue);
                    }
                }
            }
            MongoCursorHandle handle = gateway.find(
                    config, collection, query, sort, BATCH_SIZE, timeout);
            return new MongoEtlSourceCursor(handle, columns, keyIndex);
        } catch (MongoSchemaException e) {
            throw new EtlProgramException(e.getMessage());
        } catch (EtlProgramException | EtlSourceException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // A stored resume value that cannot be decoded back to a BSON value is a defect
            // in the task or its checkpoint, not a database failure.
            throw new EtlProgramException(e.getMessage());
        } catch (RuntimeException e) {
            throw sourceFailure(e);
        }
    }

    @Override
    public EtlSourceProbe probe(EtlTask task, int timeoutSeconds) {
        List<EtlSourceIssue> issues = new ArrayList<>();
        String collection;
        try {
            collection = collection(task);
        } catch (EtlProgramException e) {
            issues.add(error("error.etl.collection.required", null));
            return new EtlSourceProbe(List.of(), -1, issues);
        }

        Document filter;
        try {
            filter = filter(task);
        } catch (MongoFilterException e) {
            boolean blocked = "error.etl.mongo.filter.blocked".equals(e.getMessage());
            issues.add(error(blocked ? "etl.precheck.mongo.filter.blocked"
                    : "etl.precheck.mongo.filter.error", e.getMessage()));
            return new EtlSourceProbe(List.of(), -1, issues);
        }

        try {
            if (!gateway.collectionExists(config, collection)) {
                issues.add(error("etl.precheck.mongo.collection.missing", collection));
                return new EtlSourceProbe(List.of(), -1, issues);
            }
            issues.add(ok("etl.precheck.mongo.collection.ok", collection));
            issues.add(ok("etl.precheck.mongo.filter.ok", null));

            List<Document> samples = gateway.sample(
                    config, collection, filter, SAMPLE_LIMIT, timeoutSeconds);
            if (samples == null || samples.isEmpty()) {
                issues.add(error("etl.precheck.mongo.sample.empty", collection));
                return new EtlSourceProbe(List.of(), -1, issues);
            }
            List<EtlSourceColumn> columns;
            try {
                columns = MongoSchemaInferrer.infer(samples);
            } catch (MongoSchemaException e) {
                String code = "error.etl.mongo.field.duplicate".equals(e.messageKey())
                        ? "etl.precheck.mongo.field.duplicate"
                        : "etl.precheck.mongo.field.invalid";
                issues.add(error(code, e.detail()));
                return new EtlSourceProbe(List.of(), -1, issues);
            }
            issues.add(ok("etl.precheck.mongo.sample.ok", String.valueOf(samples.size())));

            int keyIndex = checkResumeKey(columns, samples, task, issues);
            return new EtlSourceProbe(columns, keyIndex, issues);
        } catch (RuntimeException e) {
            throw sourceFailure(e);
        }
    }

    @Override
    public void close() {
        gateway.close();
    }

    // --- task shape ------------------------------------------------------------------------

    private static String collection(EtlTask task) {
        String collection = task.getSourceCollection();
        if (collection == null || collection.isBlank()) {
            throw new EtlProgramException("error.etl.collection.required");
        }
        String trimmed = collection.trim();
        if (trimmed.startsWith("$")) {
            // Collection names starting with $ are reserved for the server's own metadata.
            throw new EtlProgramException("error.etl.collection.invalid");
        }
        return trimmed;
    }

    private static Document filter(EtlTask task) {
        return MongoFilterParser.parse(task.getSourceFilter());
    }

    private static boolean resumable(EtlTask task) {
        String key = task.getResumeKeyColumn();
        return task.getWriteMode() != EtlWriteMode.OVERWRITE && key != null && !key.isBlank();
    }

    private static int timeout(EtlTask task) {
        Integer timeout = task.getTimeoutSeconds();
        return timeout == null || timeout <= 0 ? 0 : timeout;
    }

    private static List<EtlSourceColumn> infer(List<Document> samples) {
        List<EtlSourceColumn> columns = MongoSchemaInferrer.infer(samples);
        if (columns.isEmpty()) {
            // A target table with no columns cannot be created; failing here leaves the
            // previous target contents untouched, even in overwrite mode.
            throw new EtlProgramException("error.etl.mongo.sample.empty");
        }
        return columns;
    }

    private static int resumeKeyIndex(List<EtlSourceColumn> columns, EtlTask task,
                                      boolean resumable) {
        if (!resumable) {
            return -1;
        }
        String key = task.getResumeKeyColumn().trim();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(key)) {
                return i + 1;
            }
        }
        throw new EtlProgramException(
                "resume key '" + key + "' is not a field of the sampled documents");
    }

    private static int checkResumeKey(List<EtlSourceColumn> columns, List<Document> samples,
                                      EtlTask task, List<EtlSourceIssue> issues) {
        String key = task.getResumeKeyColumn();
        if (key == null || key.isBlank()) {
            return -1;
        }
        String trimmed = key.trim();
        int index = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(trimmed)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            issues.add(error("etl.precheck.mongo.resume.error", trimmed));
            return -1;
        }
        Object value = samples.get(0).get(trimmed);
        if (value == null) {
            issues.add(warn("etl.precheck.mongo.resume.null", trimmed));
            return index + 1;
        }
        try {
            MongoResumeKeyCodec.encode(value);
            issues.add(ok("etl.precheck.mongo.resume.ok", trimmed));
        } catch (IllegalArgumentException e) {
            boolean tooLong = "error.etl.mongo.resume.tooLong".equals(e.getMessage());
            issues.add(error(tooLong ? "etl.precheck.mongo.resume.tooLong"
                    : "etl.precheck.mongo.resume.error", trimmed));
        }
        return index + 1;
    }

    private static EtlSourceException sourceFailure(RuntimeException e) {
        String message = e.getMessage();
        String detail = message == null || message.isBlank()
                ? e.getClass().getSimpleName() : message;
        return new EtlSourceException("etl.precheck.mongo.connection.error", detail, e);
    }

    private static EtlSourceIssue ok(String code, String detail) {
        return new EtlSourceIssue(EtlPrecheckResult.Level.OK, code, detail);
    }

    private static EtlSourceIssue warn(String code, String detail) {
        return new EtlSourceIssue(EtlPrecheckResult.Level.WARN, code, detail);
    }

    private static EtlSourceIssue error(String code, String detail) {
        return new EtlSourceIssue(EtlPrecheckResult.Level.ERROR, code, detail);
    }
}
