package com.qqmu.jync.service.migration.mongo;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.service.etl.EtlSourceColumn;
import com.qqmu.jync.service.etl.mongo.MongoSchemaException;
import com.qqmu.jync.service.etl.mongo.MongoSchemaInferrer;
import com.qqmu.jync.service.migration.MigrationCancellation;
import com.qqmu.jync.service.migration.MigrationPrecheckResult;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Check;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Level;
import com.qqmu.jync.service.migration.MigrationProgressListener;
import com.qqmu.jync.service.migration.MigrationRunResult;
import com.qqmu.jync.service.migration.MigrationTypeHandler;
import com.qqmu.jync.service.migration.MigrationWriteMode;
import com.qqmu.jync.service.migration.connection.KingbaseConnectionFactory;
import com.qqmu.jync.service.migration.kingbase.KingbaseTableName;
import com.qqmu.jync.service.migration.kingbase.KingbaseTableWriter;
import com.qqmu.jync.service.migration.kingbase.KingbaseWriterFactory;

/** MongoDB → KingbaseES: sampled columns, {@code _id} as the primary key, batched inserts. */
@Component
public class MongoToKingbaseHandler implements MigrationTypeHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MongoMigrationGatewayFactory gatewayFactory;
    private final KingbaseConnectionFactory connectionFactory;
    private final KingbaseWriterFactory writerFactory;
    private final MongoToKingbaseEngine engine;

    public MongoToKingbaseHandler(MongoMigrationGatewayFactory gatewayFactory,
                                  KingbaseConnectionFactory connectionFactory,
                                  KingbaseWriterFactory writerFactory) {
        this.gatewayFactory = gatewayFactory;
        this.connectionFactory = connectionFactory;
        this.writerFactory = writerFactory;
        this.engine = new MongoToKingbaseEngine(gatewayFactory, connectionFactory, writerFactory);
    }

    @Override
    public MigrationType type() {
        return MigrationType.MONGODB_TO_KINGBASE;
    }

    @Override
    public Set<MigrationConnectionKind> sourceKinds() {
        return Set.of(MigrationConnectionKind.MONGODB);
    }

    @Override
    public Set<MigrationConnectionKind> targetKinds() {
        return Set.of(MigrationConnectionKind.KINGBASE);
    }

    @Override
    public JsonNode defaultOptions() {
        return MongoToKingbaseOptions.parse(JSON.createObjectNode()).toJson();
    }

    /** Accepts both canonical camelCase names and the form's {@code opt.*} prefix. */
    @Override
    public JsonNode parseOptions(ObjectNode formOptions) {
        ObjectNode merged = (ObjectNode) defaultOptions().deepCopy();
        if (formOptions != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = formOptions.properties().iterator();
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
        return MongoToKingbaseOptions.parse(merged).toJson();
    }

    @Override
    public MigrationPrecheckResult precheck(MigrationTask task, JsonNode optionsNode,
                                            MigrationConnection source,
                                            MigrationConnection target) {
        MongoToKingbaseOptions options = MongoToKingbaseOptions.parse(optionsNode);
        List<Check> checks = new ArrayList<>();

        MongoMigrationGateway sourceGateway;
        try {
            sourceGateway = gatewayFactory.open(source);
        } catch (RuntimeException e) {
            return result(checks, Level.ERROR, "migration.precheck.source.unreachable",
                    describe(e));
        }
        try (sourceGateway) {
            List<MongoCollectionInfo> discovered;
            try {
                sourceGateway.ping();
                discovered = sourceGateway.listCollections();
            } catch (RuntimeException e) {
                return result(checks, Level.ERROR, "migration.precheck.source.unreachable",
                        describe(e));
            }
            checks.add(new Check(Level.OK, "migration.precheck.source.reachable",
                    source.getName()));

            List<MongoCollectionInfo> collections = MongoMigrationSupport.matching(discovered,
                    options.getCollectionsText());
            if (collections.isEmpty()) {
                checks.add(new Check(Level.WARN, "migration.precheck.discovery.empty",
                        String.join(", ", options.patterns())));
                return new MigrationPrecheckResult(checks);
            }
            if (collections.size() > options.getMaxCollections()) {
                checks.add(new Check(Level.WARN, "migration.precheck.discovery.capped",
                        collections.size() + " > " + options.getMaxCollections()));
                collections = collections.subList(0, options.getMaxCollections());
            }

            Connection jdbc;
            try {
                jdbc = connectionFactory.open(target);
            } catch (SQLException e) {
                return result(checks, Level.ERROR, "migration.precheck.target.unreachable",
                        describe(e));
            }
            try {
                precheckTarget(checks, collections, options, target, jdbc, sourceGateway);
            } finally {
                closeQuietly(jdbc);
            }
        }
        return new MigrationPrecheckResult(checks);
    }

    private void precheckTarget(List<Check> checks, List<MongoCollectionInfo> collections,
                                MongoToKingbaseOptions options, MigrationConnection target,
                                Connection jdbc, MongoMigrationGateway sourceGateway) {
        String schema = KingbaseTableName.resolveSchema(target.getSchemaName(), jdbc);
        try {
            KingbaseTableName.validateSchema(schema);
        } catch (IllegalArgumentException e) {
            checks.add(new Check(Level.ERROR, "migration.precheck.schema.invalid",
                    String.valueOf(schema)));
            return;
        }
        KingbaseTableWriter writer = writerFactory.open(jdbc, schema);
        try {
            if (!writer.schemaExists()) {
                checks.add(new Check(Level.ERROR, "migration.precheck.schema.missing", schema));
                return;
            }
        } catch (SQLException e) {
            checks.add(new Check(Level.ERROR, "migration.precheck.target.unreachable",
                    describe(e)));
            return;
        }
        checks.add(new Check(Level.OK, "migration.precheck.target.reachable", target.getName()));
        for (MongoCollectionInfo info : collections) {
            if (!info.isCollection()) {
                checks.add(new Check(Level.WARN, "migration.precheck.mongo.view",
                        info.name() + " (" + info.type() + ")"));
                continue;
            }
            String targetName;
            try {
                targetName = KingbaseTableName.resolve(options.getTargetNaming(),
                        options.getTargetPrefix(), options.getTargetSuffix(), info.name());
            } catch (IllegalArgumentException e) {
                checks.add(new Check(Level.ERROR, "migration.precheck.index.targetName",
                        info.name() + " -> " + e.getMessage()));
                continue;
            }
            List<EtlSourceColumn> columns;
            try {
                columns = inferColumns(sourceGateway, info.name(), options);
            } catch (MongoSchemaException e) {
                checks.add(new Check(Level.ERROR, "migration.precheck.mongo.field",
                        e.detail()));
                continue;
            } catch (SampleEmptyException e) {
                checks.add(new Check(Level.WARN, "migration.precheck.sample.empty",
                        info.name()));
                continue;
            } catch (RuntimeException e) {
                // A read failure while sampling is a precheck result, not a crash: the task
                // page must still render the rest of the checks.
                checks.add(new Check(Level.ERROR, "migration.precheck.sample.failed",
                        info.name() + ": " + describe(e)));
                continue;
            }
            if (columns.stream().noneMatch(column -> "_id".equals(column.name()))) {
                checks.add(new Check(Level.ERROR, "migration.precheck.mongo.idMissing",
                        info.name()));
                continue;
            }
            String tooLong = KingbaseTableName.firstColumnNameTooLong(
                    columns.stream().map(EtlSourceColumn::name).toList());
            if (tooLong != null) {
                checks.add(new Check(Level.ERROR, "migration.precheck.mongo.fieldTooLong",
                        info.name() + "." + tooLong));
                continue;
            }
            if (options.getWriteMode() == MigrationWriteMode.FAIL_IF_EXISTS) {
                if (tableExists(writer, targetName, checks)) {
                    checks.add(new Check(Level.ERROR,
                            "migration.precheck.table.targetExists", targetName));
                }
            } else if (options.getWriteMode() == MigrationWriteMode.APPEND
                    && tableExists(writer, targetName, checks)) {
                compareColumns(writer, columns, targetName, checks);
                if (options.isUpsertOnId() && !hasUniqueId(writer, targetName, checks)) {
                    checks.add(new Check(Level.ERROR,
                            "migration.precheck.table.idNotUnique", targetName));
                }
            }
        }
    }

    private static List<EtlSourceColumn> inferColumns(MongoMigrationGateway gateway,
                                                      String collection,
                                                      MongoToKingbaseOptions options) {
        List<Document> sample = gateway.sample(collection, options.getSampleLimit());
        if (sample == null || sample.isEmpty()) {
            throw new SampleEmptyException();
        }
        return MongoSchemaInferrer.infer(sample);
    }

    private static boolean tableExists(KingbaseTableWriter writer, String table,
                                       List<Check> checks) {
        try {
            return writer.tableExists(table);
        } catch (SQLException e) {
            checks.add(new Check(Level.ERROR, "migration.precheck.target.unreachable",
                    describe(e)));
            return false;
        }
    }

    private static void compareColumns(KingbaseTableWriter writer,
                                       List<EtlSourceColumn> sourceColumns, String table,
                                       List<Check> checks) {
        List<ColumnMeta> targetColumns;
        try {
            targetColumns = writer.targetColumns(table);
        } catch (SQLException e) {
            checks.add(new Check(Level.ERROR, "migration.precheck.target.unreachable",
                    describe(e)));
            return;
        }
        Map<String, ColumnMeta> byName = new java.util.LinkedHashMap<>();
        for (ColumnMeta column : targetColumns) {
            byName.put(column.getName(), column);
        }
        Map<String, EtlSourceColumn> sourceByName = new java.util.LinkedHashMap<>();
        for (EtlSourceColumn column : sourceColumns) {
            sourceByName.put(column.name(), column);
        }
        // The writer binds columns by the exact inferred name, so a case-folded match would
        // pass the precheck and then fail on the first batch.
        for (EtlSourceColumn source : sourceColumns) {
            ColumnMeta target = byName.get(source.name());
            if (target == null) {
                checks.add(new Check(Level.ERROR, "migration.precheck.table.missingColumn",
                        table + "." + source.name()));
            } else if (!compatible(target.getJdbcType(), source.jdbcType())) {
                checks.add(new Check(Level.WARN, "migration.precheck.table.typeMismatch",
                        table + "." + source.name()));
            }
        }
        for (ColumnMeta target : targetColumns) {
            if (!sourceByName.containsKey(target.getName())
                    && !target.isNullable()
                    && target.getDefaultValue() == null) {
                checks.add(new Check(Level.ERROR,
                        "migration.precheck.table.extraRequiredColumn",
                        table + "." + target.getName()));
            }
        }
    }

    private static boolean hasUniqueId(KingbaseTableWriter writer, String table,
                                       List<Check> checks) {
        try {
            return writer.hasUniqueIndex(table, "_id");
        } catch (SQLException e) {
            checks.add(new Check(Level.ERROR, "migration.precheck.target.unreachable",
                    describe(e)));
            // The connection error is already reported; do not add a second, misleading one.
            return true;
        }
    }

    private static boolean compatible(int target, int source) {
        if (target == source) {
            return true;
        }
        return (isText(target) && isText(source)) || (isNumeric(target) && isNumeric(source));
    }

    private static boolean isText(int type) {
        return switch (type) {
            case java.sql.Types.VARCHAR, java.sql.Types.NVARCHAR, java.sql.Types.CHAR,
                    java.sql.Types.NCHAR, java.sql.Types.CLOB, java.sql.Types.NCLOB,
                    java.sql.Types.LONGVARCHAR, java.sql.Types.LONGNVARCHAR -> true;
            default -> false;
        };
    }

    private static boolean isNumeric(int type) {
        return switch (type) {
            case java.sql.Types.INTEGER, java.sql.Types.BIGINT, java.sql.Types.SMALLINT,
                    java.sql.Types.TINYINT, java.sql.Types.DOUBLE, java.sql.Types.FLOAT,
                    java.sql.Types.REAL, java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> true;
            default -> false;
        };
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

    private static void closeQuietly(Connection connection) {
        MongoMigrationSupport.closeQuietly(connection);
    }

    private static String describe(Throwable e) {
        return MongoMigrationSupport.describe(e);
    }

    /** The sample had no fields at all, so there is nothing to create a table from. */
    private static final class SampleEmptyException extends RuntimeException {
    }
}
