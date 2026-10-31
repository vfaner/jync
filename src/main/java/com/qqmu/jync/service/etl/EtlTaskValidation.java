package com.qqmu.jync.service.etl;

import java.util.Objects;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.etl.mongo.MongoFilterParser;

/**
 * The checks a task must pass both to be saved and to be prechecked.
 *
 * <p>Shared so the two cannot drift: a precheck that accepts what save rejects (or the reverse)
 * only teaches the operator the wrong lesson. Save adds its own name/cron/timeout checks on top.
 */
final class EtlTaskValidation {

    private EtlTaskValidation() {
    }

    static void validateExtraction(EtlTask task, DatabaseConfigService configs) {
        if (task.getSourceDbId() == null || task.getTargetDbId() == null) {
            throw new IllegalArgumentException("error.etl.connection.required");
        }
        var source = configs.findById(task.getSourceDbId()).orElse(null);
        if (source == null) {
            throw new IllegalArgumentException("error.etl.source.missing");
        }
        var target = configs.findById(task.getTargetDbId()).orElse(null);
        if (target == null) {
            throw new IllegalArgumentException("error.etl.target.missing");
        }
        if (!target.supportsEtlTarget()) {
            // Elasticsearch and MongoDB expose a read-only SQL interface: the task would be
            // accepted here and fail on its first write, after a run had already started.
            throw new IllegalArgumentException("error.etl.target.readOnly");
        }
        if (Objects.equals(task.getSourceDbId(), task.getTargetDbId())) {
            throw new IllegalArgumentException("error.etl.same.database");
        }
        if (isNativeMongo(source)) {
            validateMongoExtraction(task);
        } else if (task.getSourceSql() == null || task.getSourceSql().isBlank()) {
            throw new IllegalArgumentException("error.etl.sql.required");
        }
        if (task.getTargetTable() == null || task.getTargetTable().isBlank()) {
            throw new IllegalArgumentException("error.etl.targetTable.required");
        }
        for (String part : task.getTargetTable().trim().split("\\.")) {
            if (!EtlSqlSupport.PLAIN_IDENTIFIER.matcher(part).matches()) {
                throw new IllegalArgumentException("error.etl.targetTable.invalid");
            }
        }
        if (task.getWriteMode() == EtlWriteMode.RANGE_OVERWRITE
                && (task.getRangeCondition() == null || task.getRangeCondition().isBlank())) {
            throw new IllegalArgumentException("error.etl.range.required");
        }
        if (task.getResumeKeyColumn() != null && !task.getResumeKeyColumn().isBlank()
                && !EtlSqlSupport.RESUME_KEY.matcher(task.getResumeKeyColumn().trim()).matches()) {
            throw new IllegalArgumentException("error.etl.resumeKey.invalid");
        }
    }

    static boolean isNativeMongo(DatabaseConfig config) {
        return config != null && config.effectiveType() == DatabaseType.MONGODB_NATIVE;
    }

    private static void validateMongoExtraction(EtlTask task) {
        String collection = task.getSourceCollection();
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("error.etl.collection.required");
        }
        String trimmed = collection.trim();
        if (trimmed.startsWith("$") || trimmed.indexOf('\0') >= 0
                || trimmed.length() > 255) {
            throw new IllegalArgumentException("error.etl.collection.invalid");
        }
        MongoFilterParser.parse(task.getSourceFilter());
    }
}
