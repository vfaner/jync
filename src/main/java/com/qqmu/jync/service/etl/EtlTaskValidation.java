package com.qqmu.jync.service.etl;

import java.util.List;
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

    /**
     * The connection checks a task and a batch share. Returns the source so callers that need
     * its type do not look it up twice.
     */
    static DatabaseConfig validateConnections(DatabaseConfigService configs,
                                              Long sourceDbId, Long targetDbId) {
        if (sourceDbId == null || targetDbId == null) {
            throw new IllegalArgumentException("error.etl.connection.required");
        }
        var source = configs.findById(sourceDbId).orElse(null);
        if (source == null) {
            throw new IllegalArgumentException("error.etl.source.missing");
        }
        var target = configs.findById(targetDbId).orElse(null);
        if (target == null) {
            throw new IllegalArgumentException("error.etl.target.missing");
        }
        if (!target.supportsEtlTarget()) {
            // Elasticsearch and MongoDB expose a read-only SQL interface: the task would be
            // accepted here and fail on its first write, after a run had already started.
            throw new IllegalArgumentException("error.etl.target.readOnly");
        }
        if (Objects.equals(sourceDbId, targetDbId)) {
            throw new IllegalArgumentException("error.etl.same.database");
        }
        return source;
    }

    static void validateExtraction(EtlTask task, DatabaseConfigService configs) {
        DatabaseConfig source = validateConnections(configs,
                task.getSourceDbId(), task.getTargetDbId());
        // A placeholder nothing can answer would otherwise fail on every scheduled run. The
        // Mongo collection and filter are not SQL, so a native source scans only pre/post SQL.
        EtlSqlVariables.validateReferences(task, !isNativeMongo(source));
        if (isNativeMongo(source)) {
            validateMongoExtraction(task);
        } else {
            if (task.getSourceSql() == null || task.getSourceSql().isBlank()) {
                throw new IllegalArgumentException("error.etl.sql.required");
            }
            // Refuse a script that cannot run while the operator is still on the form, rather
            // than repeating the same failure on every scheduled run.
            EtlSqlScript.validate(task.getSourceSql());
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
        if (task.getWriteMode() == EtlWriteMode.UPSERT) {
            List<String> keys = EtlUpsertSql.parse(task.getUpsertKeyColumns());
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("error.etl.upsertKey.required");
            }
            for (String key : keys) {
                if (!EtlSqlSupport.RESUME_KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException("error.etl.upsertKey.invalid");
                }
            }
        }
        // Only the modes that resume read the key. The form hides the field for the others
        // but still submits it, so a value left there must not block a save over a field the
        // operator can no longer see.
        if (resumable(task)
                && !EtlSqlSupport.RESUME_KEY.matcher(task.getResumeKeyColumn().trim()).matches()) {
            throw new IllegalArgumentException("error.etl.resumeKey.invalid");
        }
        if (!isNativeMongo(source)) {
            validateResumableWrapper(task, source);
        }
    }

    /**
     * SQL Server and DB2 refuse a top-level {@code WITH} once the extraction sits inside the
     * resume wrapper. Refusing it here keeps the form from saving a task that can only fail at
     * run time, and gives the operator a message that names the cause.
     *
     * <p>A top-level {@code ORDER BY} is not refused: a redundant one is dropped by
     * {@link EtlSqlSupport#derivedTableSource}, and one that carries {@code TOP / OFFSET /
     * FETCH} is legal inside a derived table — rejecting it would refuse statements those
     * products accept.
     */
    static void validateResumableWrapper(EtlTask task, DatabaseConfig source) {
        if (!resumable(task) || source == null || isNativeMongo(source)
                || !EtlSqlSupport.wrapsAsDerivedTable(source)) {
            return;
        }
        String select = EtlSqlScript.lastStatement(task.getSourceSql());
        if (select != null && EtlSqlScript.startsWithWith(select)) {
            throw new IllegalArgumentException("error.etl.resume.cte");
        }
    }

    private static boolean resumable(EtlTask task) {
        return task.getWriteMode() != null && task.getWriteMode().supportsResume()
                && task.getResumeKeyColumn() != null && !task.getResumeKeyColumn().isBlank();
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
