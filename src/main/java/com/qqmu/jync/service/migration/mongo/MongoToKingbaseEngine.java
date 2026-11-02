package com.qqmu.jync.service.migration.mongo;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bson.Document;

import com.fasterxml.jackson.databind.JsonNode;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.service.etl.EtlSourceColumn;
import com.qqmu.jync.service.etl.mongo.MongoSchemaInferrer;
import com.qqmu.jync.service.etl.mongo.MongoValueRenderer;
import com.qqmu.jync.service.migration.MigrationCancellation;
import com.qqmu.jync.service.migration.MigrationCancelledException;
import com.qqmu.jync.service.migration.MigrationProgressListener;
import com.qqmu.jync.service.migration.MigrationRunResult;
import com.qqmu.jync.service.migration.MigrationRunResult.IndexResult;
import com.qqmu.jync.service.migration.MigrationRunResult.Status;
import com.qqmu.jync.service.migration.MigrationWriteMode;
import com.qqmu.jync.service.migration.connection.KingbaseConnectionFactory;
import com.qqmu.jync.service.migration.kingbase.KingbaseTableName;
import com.qqmu.jync.service.migration.kingbase.KingbaseTableWriter;
import com.qqmu.jync.service.migration.kingbase.KingbaseWriterFactory;

/**
 * Copies MongoDB collections into KingbaseES tables.
 *
 * <p>The target schema is inferred from a bounded sample, because BSON is schemaless and the
 * table must exist before the first row is written. Nested documents and arrays become JSON
 * text; they are deliberately not expanded into columns.
 *
 * <p>Each collection is independent: a failed table does not stop later collections unless
 * {@code continueOnError} is off, and a cancelled or failed collection keeps its partial
 * counters in the run history.
 */
final class MongoToKingbaseEngine {

    private final MongoMigrationGatewayFactory gatewayFactory;
    private final KingbaseConnectionFactory connectionFactory;
    private final KingbaseWriterFactory writerFactory;

    MongoToKingbaseEngine(MongoMigrationGatewayFactory gatewayFactory,
                          KingbaseConnectionFactory connectionFactory,
                          KingbaseWriterFactory writerFactory) {
        this.gatewayFactory = gatewayFactory;
        this.connectionFactory = connectionFactory;
        this.writerFactory = writerFactory;
    }

    MigrationRunResult run(MigrationTask task, JsonNode optionsNode,
                           MigrationConnection source, MigrationConnection target,
                           MigrationCancellation cancellation,
                           MigrationProgressListener progress) {
        MongoToKingbaseOptions options;
        try {
            options = MongoToKingbaseOptions.parse(optionsNode);
        } catch (RuntimeException e) {
            return failedRun("invalid options: " + describe(e));
        }

        MongoMigrationGateway sourceGateway = null;
        Connection connection = null;
        try {
            sourceGateway = gatewayFactory.open(source);
            connection = connectionFactory.open(target);
            // The writer commits the DDL and every batch, and rolls a failed batch back.
            // DriverManager hands out auto-commit connections, where commit() is an error.
            connection.setAutoCommit(false);
            String schema = KingbaseTableName.resolveSchema(target.getSchemaName(), connection);
            KingbaseTableName.validateSchema(schema);
            KingbaseTableWriter writer = writerFactory.open(connection, schema);
            return runWithGateways(options, sourceGateway, writer, cancellation, progress);
        } catch (SQLException e) {
            return failedRun(sqlMessage(e));
        } catch (RuntimeException e) {
            return failedRun(describe(e));
        } finally {
            closeQuietly(connection);
            closeQuietly(sourceGateway);
        }
    }

    private MigrationRunResult runWithGateways(MongoToKingbaseOptions options,
                                               MongoMigrationGateway sourceGateway,
                                               KingbaseTableWriter writer,
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
            runWarnings.add("WARN: " + collections.size()
                    + " collections matched, only the first " + options.getMaxCollections()
                    + " will be migrated");
            collections = collections.subList(0, options.getMaxCollections());
        }

        List<IndexResult> results = new ArrayList<>();
        Set<String> targetNames = new HashSet<>();
        boolean cancelled = false;
        for (MongoCollectionInfo info : collections) {
            String targetName;
            try {
                targetName = KingbaseTableName.resolve(options.getTargetNaming(),
                        options.getTargetPrefix(), options.getTargetSuffix(), info.name());
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
                        "target table name is already used by another source collection"));
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
                results.add(migrateCollection(options, sourceGateway, writer, info.name(),
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

    private IndexResult migrateCollection(MongoToKingbaseOptions options,
                                          MongoMigrationGateway sourceGateway,
                                          KingbaseTableWriter writer,
                                          String sourceName, String targetName,
                                          MigrationCancellation cancellation,
                                          MongoMigrationSupport.Progress counters,
                                          MigrationProgressListener progress) {
        try {
            List<Document> sample = sourceGateway.sample(sourceName, options.getSampleLimit());
            if (sample == null || sample.isEmpty()) {
                return new IndexResult(sourceName, targetName, Status.SKIPPED, 0, 0,
                        "no documents to infer columns from");
            }
            List<EtlSourceColumn> columns = MongoSchemaInferrer.infer(sample);
            EtlSourceColumn idColumn = idColumn(columns);
            if (idColumn == null) {
                throw new IllegalStateException(
                        "sampled documents have no _id field, so the table cannot be keyed");
            }
            TableMeta table = tableMeta(columns, options.isCreatePrimaryKey());

            long existingTargetCount = 0;
            boolean exists = writer.tableExists(targetName);
            if (exists) {
                if (options.getWriteMode() == MigrationWriteMode.FAIL_IF_EXISTS) {
                    throw new IllegalStateException(
                            "target table already exists: " + targetName);
                }
                if (options.getWriteMode() == MigrationWriteMode.OVERWRITE) {
                    writer.dropTable(targetName);
                    exists = false;
                } else {
                    existingTargetCount = writer.countRows(targetName);
                }
            }
            if (!exists) {
                writer.createTable(table, targetName);
            }

            copyDocuments(options, sourceGateway, writer, sourceName, targetName, table, columns,
                    cancellation, counters, progress);

            if (options.isVerifyCount() && !verifyCount(options, sourceGateway, writer,
                    sourceName, targetName, existingTargetCount, counters)) {
                throw new IllegalStateException(
                        "row count mismatch: source and target differ");
            }

            List<String> warnings = new ArrayList<>();
            if (options.getVerifySampleSize() > 0) {
                int missing = verifySample(options, sourceGateway, writer, sourceName,
                        targetName, table, idColumn);
                if (missing > 0) {
                    warnings.add("WARN: " + missing
                            + " sampled _id values are missing from the target");
                }
            }
            return new IndexResult(sourceName, targetName, Status.SUCCESS, counters.read,
                    counters.written, warnings.isEmpty() ? null : String.join("; ", warnings));
        } catch (SQLException e) {
            throw new IllegalStateException(sqlMessage(e), e);
        }
    }

    private static void copyDocuments(MongoToKingbaseOptions options,
                                      MongoMigrationGateway sourceGateway,
                                      KingbaseTableWriter writer,
                                      String sourceName, String targetName, TableMeta table,
                                      List<EtlSourceColumn> columns,
                                      MigrationCancellation cancellation,
                                      MongoMigrationSupport.Progress counters,
                                      MigrationProgressListener progress) throws SQLException {
        try (MongoDocumentCursor cursor = sourceGateway.find(sourceName,
                options.getBatchSize(), true);
             KingbaseTableWriter.BatchWriter batch = writer.openBatch(targetName, table,
                     options.isUpsertOnId())) {
            int pendingRows = 0;
            while (cursor.hasNext()) {
                Document document = cursor.next();
                if (pendingRows >= options.getBatchSize()) {
                    counters.written += batch.flush();
                    pendingRows = 0;
                    cancellation.check();
                    progress.onProgress(sourceName, counters.read, counters.written);
                }
                // A fresh list per row: the writer binds values immediately, and a batch
                // cursor that retained the list would re-bind the last row's values.
                List<Object> row = new ArrayList<>(columns.size());
                for (EtlSourceColumn column : columns) {
                    row.add(MongoValueRenderer.render(column, document.get(column.name())));
                }
                batch.add(row);
                counters.read++;
                pendingRows++;
            }
            if (pendingRows > 0) {
                counters.written += batch.flush();
                progress.onProgress(sourceName, counters.read, counters.written);
            }
        }
    }

    private static boolean verifyCount(MongoToKingbaseOptions options,
                                       MongoMigrationGateway sourceGateway,
                                       KingbaseTableWriter writer,
                                       String sourceName, String targetName,
                                       long existingTargetCount, MongoMigrationSupport.Progress counters)
            throws SQLException {
        long targetCount = writer.countRows(targetName);
        if (options.getWriteMode() == MigrationWriteMode.APPEND) {
            if (options.isUpsertOnId()) {
                // An upsert may overwrite a row instead of adding one, so the arithmetic
                // of "existing + written" would report a false mismatch.
                return targetCount >= sourceGateway.countDocuments(sourceName);
            }
            return targetCount >= existingTargetCount + counters.written;
        }
        return sourceGateway.countDocuments(sourceName) == targetCount;
    }

    /** Returns how many sampled {@code _id} values are absent from the target table. */
    private static int verifySample(MongoToKingbaseOptions options,
                                    MongoMigrationGateway sourceGateway,
                                    KingbaseTableWriter writer,
                                    String sourceName, String targetName, TableMeta table,
                                    EtlSourceColumn idColumn) throws SQLException {
        List<Document> sample = sourceGateway.sample(sourceName, options.getVerifySampleSize());
        ColumnMeta idMeta = table.column(idColumn.name()).orElseThrow();
        int missing = 0;
        for (Document document : sample) {
            Object id = MongoValueRenderer.render(idColumn, document.get(idColumn.name()));
            if (id == null || !writer.idExists(targetName, idMeta, id)) {
                missing++;
            }
        }
        return missing;
    }

    private static EtlSourceColumn idColumn(List<EtlSourceColumn> columns) {
        for (EtlSourceColumn column : columns) {
            if ("_id".equals(column.name())) {
                return column;
            }
        }
        return null;
    }

    private static TableMeta tableMeta(List<EtlSourceColumn> columns, boolean createPrimaryKey) {
        TableMeta table = new TableMeta();
        int ordinal = 1;
        for (EtlSourceColumn source : columns) {
            ColumnMeta column = new ColumnMeta();
            column.setName(source.name());
            column.setTypeName(source.typeName());
            column.setJdbcType(source.jdbcType());
            column.setSize(source.size());
            column.setDecimalDigits(source.decimalDigits());
            boolean key = createPrimaryKey && "_id".equals(source.name());
            column.setNullable(!key);
            column.setPrimaryKey(key);
            column.setOrdinalPosition(ordinal++);
            table.getColumns().add(column);
        }
        if (createPrimaryKey) {
            table.getPrimaryKeys().add("_id");
        }
        return table;
    }

    private static void closeQuietly(Connection connection) {
        MongoMigrationSupport.closeQuietly(connection);
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

    private static String sqlMessage(SQLException e) {
        return MongoMigrationSupport.sqlMessage(e);
    }
}
