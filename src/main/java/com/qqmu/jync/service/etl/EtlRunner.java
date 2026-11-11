package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
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
import com.qqmu.jync.service.sync.JdbcRowMapper;
import com.qqmu.jync.service.sync.UniqueViolations;
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
            boolean resumable = task.getWriteMode() != null
                    && task.getWriteMode().supportsResume()
                    && notBlank(task.getResumeKeyColumn());
            EtlCheckpointValue checkpoint = resumable
                    ? checkpointStore.load(task.getId()) : null;
            if (task.getWriteMode() == EtlWriteMode.OVERWRITE) {
                checkpointStore.clear(task.getId());
            }

            // Attribute each open to its own side: an unreachable source or target is a
            // connection failure, not a defect in the task configuration (PROGRAM).
            stage = Side.SOURCE;
            source = sourceFactory.open(sourceConfig, connections);
            stage = Side.TARGET;
            target = connections.open(targetConfig);
            target.setAutoCommit(false);

            String targetName = EtlSqlSupport.qualified(targetConfig, task.getTargetTable());

            stage = Side.SOURCE;
            try (EtlSourceCursor cursor = source.query(task, checkpoint)) {
                List<EtlSourceColumn> columns = cursor.columns();
                // Resolve the key before anything on the target is deleted: a typo must
                // not cost the operator the previous contents.
                int keyIndex = cursor.resumeKeyIndex();
                List<String> columnNames = columnNames(columns);

                stage = Side.TARGET;
                // Upsert keys are resolved against the result labels before the target is
                // created or written: a misspelled key must cost neither a table nor a row.
                List<String> upsertKeys = task.getWriteMode() == EtlWriteMode.UPSERT
                        ? EtlUpsertSql.resolve(columnNames,
                                EtlUpsertSql.parse(task.getUpsertKeyColumns()))
                        : List.of();
                boolean targetCreated = ensureTargetTable(target, targetDialect, sourceType,
                        targetConfig, task.getTargetTable(), targetName, columns, upsertKeys);
                // Pre-sql (cleanup, deletions, pre-transformations) runs after the target
                // table exists: its documented example is a DELETE on that table, which a
                // first run would otherwise fail before the table was created. It still runs
                // before the write-mode deletion and before any row is written. A failure is
                // the target's, not the program's.
                //
                // A continuation never replays it: the cursor reads only the keys after the
                // checkpoint, so a cleanup DELETE would remove the rows the first run already
                // committed and the continuation query could never bring them back.
                stage = Side.TARGET;
                if (task.getPreSql() != null && !task.getPreSql().isBlank()) {
                    if (checkpoint != null) {
                        log.warn("ETL task {} resumes after a checkpoint; skipping pre-sql "
                                + "so it cannot delete rows the first run already loaded",
                                task.getId());
                    } else {
                        executePreOrPost(target, task, task.getPreSql());
                    }
                }
                deleteForWriteMode(target, task, targetName, checkpoint);

                String insertSql = insertSql(targetName, columnNames);
                String nativeUpsertSql = task.getWriteMode() == EtlWriteMode.UPSERT
                        ? EtlUpsertSql.nativeUpsert(targetConfig.effectiveType(), targetName,
                                columnNames, upsertKeys)
                        : null;
                if (nativeUpsertSql != null && !targetCreated
                        && !EtlUpsertIndexes.coversKeys(target, targetName, upsertKeys)) {
                    // A pre-existing target without a unique index on exactly the keys cannot
                    // make a repeated key collide: ON DUPLICATE KEY UPDATE would insert a
                    // second copy instead of updating the first.
                    log.info("ETL target {} has no unique index on {}, using the emulated",
                            targetName, upsertKeys);
                    nativeUpsertSql = null;
                }
                boolean emulatedUpsert = task.getWriteMode() == EtlWriteMode.UPSERT
                        && nativeUpsertSql == null;
                String writeSql = nativeUpsertSql != null ? nativeUpsertSql : insertSql;
                String updateSql = emulatedUpsert
                        ? EtlUpsertSql.update(targetName, columnNames, upsertKeys) : null;

                // The emulation binds the SET values first, then the key, in the configured
                // key order; both lists are constant for the whole run.
                List<Integer> updateIndexes = emulatedUpsert
                        ? upsertBindingIndexes(columnNames, upsertKeys) : List.of();
                List<EtlSourceColumn> updateColumns = updateIndexes.stream()
                        .map(columns::get).toList();
                // Both upsert forms match a row by its key, so both need the key positions:
                // the emulation binds them, the NULL check rejects a row without them.
                List<Integer> keyIndexes = upsertKeys.stream()
                        .map(columnNames::indexOf).toList();
                List<EtlSourceColumn> keyColumns = keyIndexes.stream()
                        .map(columns::get).toList();

                stage = Side.TARGET;
                try (PreparedStatement insert = target.prepareStatement(writeSql);
                     PreparedStatement update = updateSql == null ? null
                             : target.prepareStatement(updateSql);
                     PreparedStatement probe = emulatedUpsert
                             ? target.prepareStatement(
                                     EtlUpsertSql.selectByKey(targetName, upsertKeys))
                             : null) {
                    int pending = 0;
                    int keyType = Types.NULL;
                    String keyValue = null;
                    Integer timeout = task.getTimeoutSeconds();
                    if (timeout != null && timeout > 0) {
                        // A statement property, not a per-row one: setting it inside the batch
                        // loop repeats the same driver call once per row.
                        insert.setQueryTimeout(timeout);
                        if (update != null) {
                            update.setQueryTimeout(timeout);
                        }
                        if (probe != null) {
                            probe.setQueryTimeout(timeout);
                        }
                    }

                    while (true) {
                        // Set before the fetch: the loop leaves the stage at TARGET after each
                        // row's insert, so a next() that throws was blamed on the target.
                        stage = Side.SOURCE;
                        if (!cursor.next()) {
                            break;
                        }
                        rowsRead++;
                        Object[] values = new Object[columns.size()];
                        for (int i = 1; i <= columns.size(); i++) {
                            values[i - 1] = cursor.getObject(i);
                        }
                        for (int index : keyIndexes) {
                            if (values[index] == null) {
                                // An upsert matches a row by its key; a NULL key matches
                                // nothing, so the row would be inserted again on every run.
                                throw new EtlProgramException(
                                        "error.etl.upsertKey.null:" + columnNames.get(index));
                            }
                        }
                        if (keyIndex > 0) {
                            keyType = columns.get(keyIndex - 1).jdbcType();
                            keyValue = cursor.getString(keyIndex);
                        }
                        stage = Side.TARGET;
                        if (emulatedUpsert) {
                            writeUpsertRow(update, insert, probe, columns, keyColumns,
                                    updateColumns, updateIndexes, keyIndexes,
                                    values, targetDialect);
                        } else {
                            bindRow(insert, columns, values, targetDialect);
                            insert.addBatch();
                        }
                        pending++;

                        if (pending >= BATCH_SIZE) {
                            stage = Side.TARGET;
                            // The emulation already executed each row's statements; only the
                            // committed transaction boundary is left to draw.
                            if (!emulatedUpsert) {
                                insert.executeBatch();
                            }
                            target.commit();
                            rowsWritten += pending;
                            pending = 0;
                            stage = Side.PROGRAM;
                            // Only after the commit: a checkpoint written for an uncommitted
                            // batch would skip those rows on restart (at-least-once, not
                            // at-most-once). keyType/keyValue hold the last row of the batch.
                            saveCheckpoint(task, resumable, keyType, keyValue);
                        }
                    }

                    if (pending > 0) {
                        stage = Side.TARGET;
                        if (!emulatedUpsert) {
                            insert.executeBatch();
                        }
                        target.commit();
                        rowsWritten += pending;
                        stage = Side.PROGRAM;
                        saveCheckpoint(task, resumable, keyType, keyValue);
                    }
                }
            }

            log.info("ETL task {} copied {} rows into {} ({} read)",
                    task.getId(), rowsWritten, targetName, rowsRead);

            // Execute post-sql (updates, calculations, post-processing). Like pre-sql it runs
            // on the target connection — including after an empty source, where the copy loop
            // never set the stage.
            stage = Side.TARGET;
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
            // A driver that fails while closing must not replace the result the caller is
            // about to record; the run already happened.
            closeQuietly(source);
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
        if (task.getWriteMode() == EtlWriteMode.UPSERT) {
            List<String> keys = EtlUpsertSql.parse(task.getUpsertKeyColumns());
            if (keys.isEmpty()) {
                throw new EtlProgramException("error.etl.upsertKey.required");
            }
            for (String key : keys) {
                if (!EtlSqlSupport.RESUME_KEY.matcher(key).matches()) {
                    throw new EtlProgramException("error.etl.upsertKey.invalid");
                }
            }
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
        } else {
            // Refuse a script that cannot run before any connection opens; the refusal is a
            // program error whose message is the i18n key.
            try {
                EtlSqlScript.validate(task.getSourceSql());
            } catch (IllegalArgumentException e) {
                throw new EtlProgramException(e.getMessage());
            }
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

    /**
     * Binds one extracted row to the insert. A zone-less wall clock goes through
     * {@link JdbcRowMapper#bindParameter}: Oracle's driver renders a {@code LocalDateTime}
     * through the JVM default zone, so a pre-1949 DST-gap value would land an hour late.
     */
    static void bindRow(PreparedStatement insert, List<EtlSourceColumn> columns,
                        Object[] values, SqlDialect dialect) throws SQLException {
        for (int i = 1; i <= columns.size(); i++) {
            Object value = values[i - 1];
            if (value == null) {
                insert.setNull(i, columns.get(i - 1).jdbcType());
            } else {
                JdbcRowMapper.bindParameter(insert, i, value, dialect);
            }
        }
    }

    /**
     * Writes one row in an emulated upsert: update by key, insert only when the key is absent.
     *
     * <p>The existence probe is what makes this correct on every target: some drivers report
     * the number of <em>changed</em> rows, so an update that matched an identical row answers
     * zero. Treating that as "missing" would then insert a second copy.
     */
    private static void writeUpsertRow(PreparedStatement update, PreparedStatement insert,
                                       PreparedStatement probe, List<EtlSourceColumn> columns,
                                       List<EtlSourceColumn> keyColumns,
                                       List<EtlSourceColumn> updateColumns,
                                       List<Integer> updateIndexes, List<Integer> keyIndexes,
                                       Object[] values, SqlDialect dialect) throws SQLException {
        if (update != null) {
            Object[] updateValues = new Object[updateIndexes.size()];
            for (int i = 0; i < updateValues.length; i++) {
                updateValues[i] = values[updateIndexes.get(i)];
            }
            bindRow(update, updateColumns, updateValues, dialect);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        if (exists(probe, keyColumns, values, keyIndexes, dialect)) {
            return;
        }
        bindRow(insert, columns, values, dialect);
        try {
            insert.executeUpdate();
        } catch (SQLException e) {
            // A concurrent writer may have inserted this key between our probe and the
            // INSERT. Only a collision on a key the target now holds is absorbed; any other
            // integrity violation must surface instead of silently dropping the row.
            if (!UniqueViolations.isUniqueViolation(e)
                    || !exists(probe, keyColumns, values, keyIndexes, dialect)) {
                throw e;
            }
        }
    }

    private static boolean exists(PreparedStatement probe, List<EtlSourceColumn> keyColumns,
                                  Object[] values, List<Integer> keyIndexes, SqlDialect dialect)
            throws SQLException {
        Object[] keyValues = new Object[keyIndexes.size()];
        for (int i = 0; i < keyValues.length; i++) {
            keyValues[i] = values[keyIndexes.get(i)];
        }
        bindRow(probe, keyColumns, keyValues, dialect);
        try (ResultSet rs = probe.executeQuery()) {
            return rs.next();
        }
    }

    /**
     * Bind order of the emulated UPDATE: every non-key column (SET clause, in result order),
     * then every key (WHERE clause, in the order the operator configured).
     */
    private static List<Integer> upsertBindingIndexes(List<String> columnNames,
                                                      List<String> keyColumns) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            if (!keyColumns.contains(columnNames.get(i))) {
                indexes.add(i);
            }
        }
        for (String key : keyColumns) {
            indexes.add(columnNames.indexOf(key));
        }
        return indexes;
    }

    // --- target -------------------------------------------------------------------------

    /** Creates the target table when it is missing; true when it did. */
    private boolean ensureTargetTable(Connection target, SqlDialect dialect,
                                      DatabaseType sourceType,
                                      DatabaseConfig targetConfig, String configuredTable,
                                      String targetName, List<EtlSourceColumn> columns,
                                      List<String> primaryKeyColumns)
            throws SQLException {
        if (EtlSqlSupport.exists(target, targetName)) {
            return false;
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
        if (!primaryKeyColumns.isEmpty()) {
            // An upsert table created by Jync must enforce the key it matches on; otherwise
            // a second run would insert the "existing" row again instead of updating it.
            ddl.append(", PRIMARY KEY (").append(String.join(", ", primaryKeyColumns)).append(')');
        }
        ddl.append(')');

        try (Statement statement = target.createStatement()) {
            statement.execute(ddl.toString());
            target.commit();
            log.info("ETL created target table {} from the extraction result metadata", targetName);
        }
        return true;
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

    private static void closeQuietly(EtlSource source) {
        if (source == null) {
            return;
        }
        try {
            source.close();
        } catch (RuntimeException e) {
            log.warn("ETL source did not close cleanly: {}", e.getMessage());
        }
    }
}
