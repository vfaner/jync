package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.etl.EtlRunResult.Side;
import com.qqmu.jync.service.etl.mongo.MongoFilterParser;

/**
 * Copies the rows of a customer-authored query into a target table.
 *
 * <p>This is deliberately not the real-time sync engine: no cursor probing, no CDC, no
 * structure comparison. The customer owns the extraction SQL and the target table; Jync only
 * streams the result, creates the target table once from the result metadata when it is
 * missing, and reports which side failed. See {@link EtlWriteMode} for what is deleted.
 */
@Service
public class EtlRunner {

    private static final Logger log = LoggerFactory.getLogger(EtlRunner.class);

    private static final int BATCH_SIZE = 500;

    private final SqlDialectFactory dialectFactory;
    private final EtlCheckpointStore checkpointStore;
    private final EtlSourceFactory sourceFactory;

    /** Test-friendly constructor: every source is read through JDBC, as before. */
    public EtlRunner(SqlDialectFactory dialectFactory, EtlCheckpointStore checkpointStore) {
        this(dialectFactory, checkpointStore, (config, connections) ->
                new JdbcEtlSource(connections.open(config), config));
    }

    @Autowired
    public EtlRunner(SqlDialectFactory dialectFactory, EtlCheckpointStore checkpointStore,
                     EtlSourceFactory sourceFactory) {
        this.dialectFactory = dialectFactory;
        this.checkpointStore = checkpointStore;
        this.sourceFactory = sourceFactory;
    }

    public EtlRunResult run(EtlTask task, DatabaseConfig sourceConfig,
                            DatabaseConfig targetConfig, EtlConnectionProvider connections) {
        long rowsRead = 0;
        long rowsWritten = 0;
        EtlSource source = null;
        Connection target = null;
        Side stage = Side.PROGRAM;

        try {
            validate(task, sourceConfig, targetConfig);

            DatabaseType sourceType = sourceConfig.effectiveType();
            SqlDialect targetDialect = dialectFactory.forType(
                    targetConfig.effectiveType(), null, targetConfig.resolvedFamily());
            boolean resumable = task.getWriteMode() != EtlWriteMode.OVERWRITE
                    && notBlank(task.getResumeKeyColumn());
            EtlCheckpointValue checkpoint = resumable
                    ? checkpointStore.load(task.getId()) : null;
            if (task.getWriteMode() == EtlWriteMode.OVERWRITE) {
                checkpointStore.clear(task.getId());
            }

            stage = Side.PROGRAM;
            source = sourceFactory.open(sourceConfig, connections);
            target = connections.open(targetConfig);
            target.setAutoCommit(false);

            // Execute pre-sql (cleanup, deletions, pre-transformations)
            if (task.getPreSql() != null && !task.getPreSql().isBlank()) {
                executePreOrPost(target, task, task.getPreSql());
            }

            String targetName = EtlSqlSupport.qualified(targetConfig, task.getTargetTable());

            stage = Side.SOURCE;
            try (EtlSourceCursor cursor = source.query(task, checkpoint)) {
                List<EtlSourceColumn> columns = cursor.columns();
                // Resolve the key before anything on the target is deleted: a typo must
                // not cost the operator the previous contents.
                int keyIndex = cursor.resumeKeyIndex();

                stage = Side.TARGET;
                ensureTargetTable(target, targetDialect, sourceType, targetConfig,
                        task.getTargetTable(), targetName, columns);
                deleteForWriteMode(target, task, targetName, checkpoint);
                String insertSql = insertSql(targetName, columnNames(columns));
                stage = Side.TARGET;
                try (PreparedStatement insert = target.prepareStatement(insertSql)) {
                    int pending = 0;
                    int keyType = Types.NULL;
                    String keyValue = null;
                    Integer timeout = task.getTimeoutSeconds();

                    stage = Side.SOURCE;
                    while (cursor.next()) {
                        stage = Side.SOURCE;
                        rowsRead++;
                        Object[] values = new Object[columns.size()];
                        for (int i = 1; i <= columns.size(); i++) {
                            values[i - 1] = cursor.getObject(i);
                        }
                        if (keyIndex > 0) {
                            keyType = columns.get(keyIndex - 1).jdbcType();
                            keyValue = cursor.getString(keyIndex);
                        }
                        stage = Side.TARGET;
                        for (int i = 1; i <= columns.size(); i++) {
                            Object value = values[i - 1];
                            if (value == null) {
                                insert.setNull(i, columns.get(i - 1).jdbcType());
                            } else {
                                insert.setObject(i, value);
                            }
                        }
                        if (timeout != null && timeout > 0) {
                            insert.setQueryTimeout(timeout);
                        }
                        insert.addBatch();
                        pending++;

                        if (pending >= BATCH_SIZE) {
                            stage = Side.TARGET;
                            insert.executeBatch();
                            target.commit();
                            rowsWritten += pending;
                            pending = 0;
                            stage = Side.PROGRAM;
                            saveCheckpoint(task, resumable, keyType, keyValue);
                        }
                    }

                    if (pending > 0) {
                        stage = Side.TARGET;
                        insert.executeBatch();
                        target.commit();
                        rowsWritten += pending;
                        stage = Side.PROGRAM;
                        saveCheckpoint(task, resumable, keyType, keyValue);
                    }
                }
            }

            log.info("ETL task {} copied {} rows into {} ({} read)",
                    task.getId(), rowsWritten, targetName, rowsRead);

            // Execute post-sql (updates, calculations, post-processing)
            if (task.getPostSql() != null && !task.getPostSql().isBlank()) {
                executePreOrPost(target, task, task.getPostSql());
            }

            return EtlRunResult.success(rowsRead, rowsWritten);
        } catch (EtlProgramException e) {
            return EtlRunResult.failure(rowsRead, rowsWritten, Side.PROGRAM, e.getMessage());
        } catch (EtlSourceException e) {
            log.warn("ETL task {} failed on the source side: {}", task.getId(), e.getMessage());
            safeRollback(target);
            return EtlRunResult.failure(rowsRead, rowsWritten, Side.SOURCE, e.getMessage());
        } catch (SQLException e) {
            log.warn("ETL task {} failed on the {} side: {}", task.getId(), stage, e.getMessage());
            safeRollback(target);
            return EtlRunResult.failure(rowsRead, rowsWritten, stage, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("ETL task {} failed in the program: {}", task.getId(), e.getMessage());
            safeRollback(target);
            return EtlRunResult.failure(rowsRead, rowsWritten, Side.PROGRAM, String.valueOf(e.getMessage()));
        } finally {
            if (source != null) {
                source.close();
            }
            closeQuietly(target);
        }
    }

    // --- preparation --------------------------------------------------------------------

    private static void validate(EtlTask task, DatabaseConfig sourceConfig,
                                 DatabaseConfig targetConfig) {
        if (task == null) {
            throw new EtlProgramException("task is missing");
        }
        if (notBlank(task.getResumeKeyColumn())
                && !EtlSqlSupport.RESUME_KEY.matcher(task.getResumeKeyColumn().trim()).matches()) {
            throw new EtlProgramException(
                    "resume key must be a plain column name: " + task.getResumeKeyColumn());
        }
        String table = task.getTargetTable();
        if (!notBlank(table)) {
            throw new EtlProgramException("target table is empty");
        }
        for (String part : table.trim().split("\\.")) {
            if (!EtlSqlSupport.PLAIN_IDENTIFIER.matcher(part).matches()) {
                throw new EtlProgramException("invalid target table name: " + table);
            }
        }
        if (task.getWriteMode() == EtlWriteMode.RANGE_OVERWRITE
                && !notBlank(task.getRangeCondition())) {
            throw new EtlProgramException(
                    "a range overwrite needs a range condition on the target table");
        }
        if (targetConfig == null || !targetConfig.supportsEtlTarget()) {
            // Defends the paths that bypass the form: a SQL-endpoint read-only type and the
            // native Mongo preset can never be the target of a copy.
            throw new EtlProgramException("error.etl.target.readOnly");
        }
        if (sourceConfig != null
                && sourceConfig.effectiveType() == DatabaseType.MONGODB_NATIVE) {
            validateMongoSource(task);
        } else if (!notBlank(task.getSourceSql())) {
            throw new EtlProgramException("extraction SQL is empty");
        }
    }

    private static void validateMongoSource(EtlTask task) {
        String collection = task.getSourceCollection();
        if (!notBlank(collection)) {
            throw new EtlProgramException("error.etl.collection.required");
        }
        String trimmed = collection.trim();
        if (trimmed.startsWith("$") || trimmed.indexOf('\0') >= 0
                || trimmed.length() > 255) {
            throw new EtlProgramException("error.etl.collection.invalid");
        }
        // Parse before opening the source so a bad filter costs no connection.
        MongoFilterParser.parse(task.getSourceFilter());
    }

    private static List<String> columnNames(List<EtlSourceColumn> columns) {
        return columns.stream().map(EtlSourceColumn::name).toList();
    }

    // --- target -------------------------------------------------------------------------

    private void ensureTargetTable(Connection target, SqlDialect dialect, DatabaseType sourceType,
                                   DatabaseConfig targetConfig, String configuredTable,
                                   String targetName, List<EtlSourceColumn> columns)
            throws SQLException {
        if (EtlSqlSupport.exists(target, targetName)) {
            return;
        }
        TableMeta table = new TableMeta();
        table.setName(configuredTable);
        for (int i = 1; i <= columns.size(); i++) {
            EtlSourceColumn source = columns.get(i - 1);
            ColumnMeta column = new ColumnMeta();
            column.setName(source.name());
            column.setJdbcType(source.jdbcType());
            column.setTypeName(source.typeName());
            column.setSize(source.size());
            column.setDecimalDigits(source.decimalDigits());
            column.setNullable(source.nullable());
            column.setOrdinalPosition(i);
            table.getColumns().add(column);
        }

        // Deliberately assembled here instead of via getCreateTableSql: the target table name
        // and the result labels are customer-facing identifiers, and the dialect quotes what
        // it is given. Quoting would create a case-sensitive table that the customer's own
        // unquoted SQL (and our unquoted INSERT) could no longer reach on products that fold
        // identifiers to upper case.
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(targetName).append(" (");
        for (int i = 0; i < table.getColumns().size(); i++) {
            ColumnMeta column = table.getColumns().get(i);
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(column.getName()).append(' ')
                    .append(dialect.mapType(column, sourceType));
            if (!column.isNullable()) {
                ddl.append(" NOT NULL");
            }
        }
        ddl.append(')');

        try (Statement statement = target.createStatement()) {
            statement.execute(ddl.toString());
            target.commit();
            log.info("ETL created target table {} from the extraction result metadata", targetName);
        }
    }

    private void deleteForWriteMode(Connection target, EtlTask task, String targetName,
                                    EtlCheckpointValue checkpoint) throws SQLException {
        if (task.getWriteMode() == EtlWriteMode.OVERWRITE) {
            execute(target, "DELETE FROM " + targetName);
        } else if (task.getWriteMode() == EtlWriteMode.RANGE_OVERWRITE && checkpoint == null) {
            // A continuation run must not delete: the first run already removed the range and
            // committed part of its replacement.
            execute(target, "DELETE FROM " + targetName
                    + " WHERE " + task.getRangeCondition().trim());
        }
    }

    private static void execute(Connection target, String sql) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.executeUpdate(sql);
            target.commit();
        }
    }

    /** Execute arbitrary SQL in the target connection, within the current transaction. */
    private void executePreOrPost(Connection target, EtlTask task, String sql) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute(sql);
            // Commit immediately for pre/post SQL to take effect; they are not part of the
            // batch insert transaction, but must succeed before we commit the data load.
            target.commit();
            log.info("Executed custom SQL in ETL task {}", task.getId());
        } catch (SQLException e) {
            log.warn("ETL task {} failed to execute custom SQL: {}", task.getId(), e.getMessage());
            target.rollback();
            throw e;
        }
    }

    private static String insertSql(String targetName, List<String> columns) {
        String names = String.join(", ", columns);
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(columns.size(), "?"));
        return "INSERT INTO " + targetName + " (" + names + ") VALUES (" + placeholders + ")";
    }

    // --- checkpoint ---------------------------------------------------------------------

    private void saveCheckpoint(EtlTask task, boolean resumable, int keyType, String keyValue) {
        if (!resumable || keyValue == null) {
            return;
        }
        checkpointStore.save(task.getId(), keyType, keyValue);
    }

    // --- helpers ------------------------------------------------------------------------

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void safeRollback(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The connection may already be broken; closing it is the only remaining step.
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful can be done at this point.
        }
    }
}
