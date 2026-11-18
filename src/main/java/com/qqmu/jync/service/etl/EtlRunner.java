package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
import com.qqmu.jync.service.converter.VendorTypeMap;
import com.qqmu.jync.service.etl.EtlRunResult.Side;
import com.qqmu.jync.service.lob.LobBinder;
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
 *
 * <p>An existing target is matched column by column, case-insensitively: the run writes the
 * intersection and reports the rest through {@link EtlRunResult#skippedColumns()} instead of
 * failing on a column the target does not have. A result sharing no column with the target is
 * refused before anything is written, and so is an upsert whose key would be pruned — every
 * such run would insert a duplicate. When the target's columns cannot be read (no metadata
 * privilege), the runner logs a warning and writes every result column, as it always did.
 */
@Service
public class EtlRunner {

    private static final Logger log = LoggerFactory.getLogger(EtlRunner.class);

    private static final int BATCH_SIZE = 500;

    /** Matches {@code EtlCheckpoint.keyValue}'s 1024-character column. */
    private static final int MAX_CHECKPOINT_KEY_LENGTH = 1024;

    private final SqlDialectFactory dialectFactory;
    private final EtlCheckpointStore checkpointStore;
    private final EtlSourceFactory sourceFactory;
    private final Clock clock;

    /** Test-friendly constructor: every source is read through JDBC, as before. */
    public EtlRunner(SqlDialectFactory dialectFactory, EtlCheckpointStore checkpointStore) {
        this(dialectFactory, checkpointStore, (config, connections) ->
                new JdbcEtlSource(connections.open(config), config), Clock.systemDefaultZone());
    }

    @Autowired
    public EtlRunner(SqlDialectFactory dialectFactory, EtlCheckpointStore checkpointStore,
                     EtlSourceFactory sourceFactory) {
        this(dialectFactory, checkpointStore, sourceFactory, Clock.systemDefaultZone());
    }

    EtlRunner(SqlDialectFactory dialectFactory, EtlCheckpointStore checkpointStore,
              EtlSourceFactory sourceFactory, Clock clock) {
        this.dialectFactory = dialectFactory;
        this.checkpointStore = checkpointStore;
        this.sourceFactory = sourceFactory;
        this.clock = clock;
    }

    public EtlRunResult run(EtlTask task, DatabaseConfig sourceConfig,
                            DatabaseConfig targetConfig, EtlConnectionProvider connections) {
        long rowsRead = 0;
        long rowsWritten = 0;
        // Labels of the result columns an existing target does not have. Empty when the table
        // is created by this run, when nothing is missing, or when its columns cannot be read.
        List<String> skippedColumns = List.of();
        EtlSource source = null;
        Connection target = null;
        Side stage = Side.PROGRAM;

        try {
            if (task == null) {
                throw new EtlProgramException("task is missing");
            }
            // One resolution for the whole run: pre-sql, the extraction, the range condition
            // and post-sql cannot straddle midnight, and an unknown name fails as this task's
            // defect before any connection is opened. Resolution comes before validation so
            // the checks judge the SQL that will actually run — a definition that empties the
            // extraction or the range condition is a program defect, not a target-side error.
            // The entity itself keeps its placeholders.
            task = EtlSqlVariables.resolve(task,
                    !EtlTaskValidation.isNativeMongo(sourceConfig), clock, targetConfig);
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
                // The bind type is the target's, not the source's: Oracle creates a CLOB for
                // a wide/LONGVARCHAR source column, and the copy loop must bind and NULL-type
                // that column as one — otherwise LobBinder never sees the values it must
                // divert and the placeholder stays on the LONG path (ORA-01461 past 4000
                // bytes, or 2000 for binary).
                List<Integer> targetTypes = targetBindTypes(columns, sourceType, targetDialect);
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
                boolean targetExists = EtlSqlSupport.exists(target, targetName);
                // An existing table defines what can be written. Extractions routinely select
                // more than the target carries (or an older table precedes a new column), so
                // the run writes the intersection and reports the rest. All of this is decided
                // before the pre-sql or the write-mode DELETE, so a configuration mistake
                // cannot empty the target it then fails to refill.
                int[] keptIndexes = keptColumnIndexes(target, task, targetName, columnNames,
                        targetExists);
                List<String> writeColumns = keptIndexes == null
                        ? columnNames : pick(columnNames, keptIndexes);
                List<Integer> writeTypes = keptIndexes == null
                        ? targetTypes : pick(targetTypes, keptIndexes);
                if (keptIndexes != null) {
                    skippedColumns = skipped(columnNames, keptIndexes);
                }
                if (writeColumns.isEmpty()) {
                    throw new EtlProgramException("error.etl.target.noColumns");
                }
                if (!skippedColumns.isEmpty()) {
                    log.warn("ETL task {}: target table {} has no column for result column(s) "
                            + "{}; writing {} of {} result columns",
                            task.getId(), targetName, String.join(", ", skippedColumns),
                            writeColumns.size(), columnNames.size());
                }
                if (task.getWriteMode() == EtlWriteMode.UPSERT) {
                    // Pruning a key column would make the upsert insert another copy on every
                    // run instead of matching the existing row; refuse instead of guessing.
                    List<String> missingKeys = EtlUpsertSql.unresolved(writeColumns, upsertKeys);
                    if (!missingKeys.isEmpty()) {
                        throw new EtlProgramException("error.etl.upsertKey.targetMissing:"
                                + String.join(", ", missingKeys));
                    }
                }
                boolean targetCreated = ensureTargetTable(target, targetDialect, sourceType,
                        targetConfig, task.getTargetTable(), targetName, columns, upsertKeys,
                        targetExists);
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

                String insertSql = insertSql(targetName, writeColumns);
                String nativeUpsertSql = task.getWriteMode() == EtlWriteMode.UPSERT
                        ? EtlUpsertSql.nativeUpsert(targetConfig.effectiveType(), targetName,
                                writeColumns, upsertKeys)
                        : null;
                if (nativeUpsertSql != null && !targetCreated
                        && !EtlUpsertIndexes.coversKeys(target, targetName, upsertKeys)) {
                    // A pre-existing target without a unique index on exactly the keys cannot
                    // make a repeated key collide: ON DUPLICATE KEY UPDATE would insert a
                    // second copy instead of updating the first.
                    log.info("ETL target {} has no unique index on {}, using the emulated upsert",
                            targetName, upsertKeys);
                    nativeUpsertSql = null;
                }
                boolean emulatedUpsert = task.getWriteMode() == EtlWriteMode.UPSERT
                        && nativeUpsertSql == null;
                String writeSql = nativeUpsertSql != null ? nativeUpsertSql : insertSql;
                String updateSql = emulatedUpsert
                        ? EtlUpsertSql.update(targetName, writeColumns, upsertKeys) : null;

                // The emulation binds the SET values first, then the key, in the configured
                // key order; both lists are constant for the whole run. The positions index
                // the written row (already pruned), not the full extraction result.
                List<Integer> updateIndexes = emulatedUpsert
                        ? upsertBindingIndexes(writeColumns, upsertKeys) : List.of();
                // Both upsert forms match a row by its key, so both need the key positions:
                // the emulation binds them, the NULL check rejects a row without them.
                List<Integer> keyIndexes = upsertKeys.stream()
                        .map(writeColumns::indexOf).toList();
                List<Integer> updateTypes = updateIndexes.stream()
                        .map(writeTypes::get).toList();
                List<Integer> keyTypes = keyIndexes.stream().map(writeTypes::get).toList();
                // Rows of the transaction being written. Only the emulation needs them: its
                // UPDATEs are queued, and their counts alone cannot tell a missing key from an
                // unchanged row, so the values are kept until the batch has been sent.
                List<Object[]> pendingUpsertRows = new ArrayList<>();

                stage = Side.TARGET;
                // Temporary LOBs bound into a batch stay alive until it executes, then are
                // freed; the finally below also covers the error paths.
                LobBinder.Tracker lobTracker = LobBinder.newTracker();
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
                        // The full result row is still read: the resume key may be a
                        // source-only column the target does not have.
                        Object[] values = new Object[columns.size()];
                        for (int i = 1; i <= columns.size(); i++) {
                            values[i - 1] = cursor.getObject(i);
                        }
                        Object[] row = keptIndexes == null ? values : pick(values, keptIndexes);
                        for (int index : keyIndexes) {
                            if (row[index] == null) {
                                // An upsert matches a row by its key; a NULL key matches
                                // nothing, so the row would be inserted again on every run.
                                // The index is a position in the written row, which is pruned;
                                // naming the full result's column there would blame an
                                // unrelated source-only column.
                                throw new EtlProgramException(
                                        "error.etl.upsertKey.null:" + writeColumns.get(index));
                            }
                        }
                        if (keyIndex > 0) {
                            keyType = columns.get(keyIndex - 1).jdbcType();
                            // The driver's own text unless it cannot be read back — Oracle
                            // renders temporal columns through NLS formats, which the resume
                            // binder cannot parse (see JdbcEtlSource.resumeKeyText). A native
                            // Mongo cursor is not a driver: its text is already
                            // MongoResumeKeyCodec's canonical encoding (an ISO instant for a
                            // date), and rewriting it to a wall clock would make decode()
                            // reject every continuation as "error.etl.mongo.resume.invalid".
                            String driverText = cursor.getString(keyIndex);
                            keyValue = sourceType == DatabaseType.MONGODB_NATIVE
                                    ? driverText
                                    : JdbcEtlSource.resumeKeyText(keyType,
                                            values[keyIndex - 1], driverText);
                            if (resumable && keyValue == null) {
                                // The continuation query filters with `key > ?`, which never
                                // matches NULL. Copying this row now and then excluding it from
                                // every continuation is silent row loss that still reports
                                // SUCCESS — fail the full run while the NULL is still visible.
                                // Clear whatever an earlier batch saved: a checkpoint would let
                                // the retry resume past this row and report success again.
                                checkpointStore.clear(task.getId());
                                throw new EtlProgramException("error.etl.resumeKey.null:"
                                        + columnNames.get(keyIndex - 1));
                            }
                            if (resumable && keyValue.length() > MAX_CHECKPOINT_KEY_LENGTH) {
                                // The checkpoint column cannot store it. Detecting this at the
                                // row keeps the run from committing a batch it can never record,
                                // which would replay (and duplicate) that batch on every retry.
                                throw new EtlProgramException("error.etl.resumeKey.tooLong:"
                                        + columnNames.get(keyIndex - 1));
                            }
                        }
                        stage = Side.TARGET;
                        if (emulatedUpsert) {
                            queueUpsertRow(update, updateTypes, updateIndexes,
                                    row, targetDialect, lobTracker, pendingUpsertRows);
                        } else {
                            bindRow(insert, writeTypes, row, targetDialect, lobTracker);
                            insert.addBatch();
                        }
                        pending++;

                        if (pending >= BATCH_SIZE) {
                            stage = Side.TARGET;
                            if (emulatedUpsert) {
                                // One round trip for the transaction's UPDATEs, then the
                                // insert path only for the keys the batch could not update.
                                flushUpsertRows(update, insert, probe, writeTypes, keyTypes,
                                        keyIndexes, pendingUpsertRows, targetDialect, lobTracker);
                            } else {
                                insert.executeBatch();
                            }
                            target.commit();
                            lobTracker.releaseAll();
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
                        if (emulatedUpsert) {
                            flushUpsertRows(update, insert, probe, writeTypes, keyTypes,
                                    keyIndexes, pendingUpsertRows, targetDialect, lobTracker);
                        } else {
                            insert.executeBatch();
                        }
                        target.commit();
                        lobTracker.releaseAll();
                        rowsWritten += pending;
                        stage = Side.PROGRAM;
                        saveCheckpoint(task, resumable, keyType, keyValue);
                    }
                } finally {
                    lobTracker.releaseAll();
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

            // A finished range refresh is one generation, not a high-water mark: the next
            // run must delete its range again and re-read it from the start, otherwise a
            // source-side update or delete inside the range would never reach the target.
            // Only a failed run keeps the checkpoint, which is what lets it continue.
            // APPEND keeps its checkpoint on purpose; OVERWRITE already cleared its own
            // before the copy.
            if (task.getWriteMode() == EtlWriteMode.RANGE_OVERWRITE) {
                checkpointStore.clear(task.getId());
            }

            return EtlRunResult.success(rowsRead, rowsWritten, skippedColumns);
        } catch (EtlProgramException e) {
            return EtlRunResult.failure(rowsRead, rowsWritten, Side.PROGRAM, e.getMessage(),
                    skippedColumns);
        } catch (EtlSourceException e) {
            log.warn("ETL task {} failed on the source side: {}", task.getId(), e.getMessage());
            safeRollback(target);
            return EtlRunResult.failure(rowsRead, rowsWritten, Side.SOURCE, e.getMessage(),
                    skippedColumns);
        } catch (SQLException e) {
            log.warn("ETL task {} failed on the {} side: {}", task.getId(), stage, e.getMessage());
            safeRollback(target);
            return EtlRunResult.failure(rowsRead, rowsWritten, stage, e.getMessage(),
                    skippedColumns);
        } catch (RuntimeException e) {
            log.warn("ETL task {} failed in the program: {}", task.getId(), e.getMessage());
            safeRollback(target);
            return EtlRunResult.failure(rowsRead, rowsWritten, Side.PROGRAM,
                    String.valueOf(e.getMessage()), skippedColumns);
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
        // Only a resumable mode reads the key, so only it judges the value: the form hides
        // the field for the others but still submits what was typed there, and a run must
        // agree with the save that accepted it.
        if (task.getWriteMode() != null && task.getWriteMode().supportsResume()
                && notBlank(task.getResumeKeyColumn())
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
            throw new EtlProgramException("error.etl.range.required");
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
            throw new EtlProgramException("error.etl.sql.required");
        } else {
            // Refuse a script that cannot run before any connection opens; the refusal is a
            // program error whose message is the i18n key.
            try {
                EtlSqlScript.validate(task.getSourceSql(), scriptFamily(sourceConfig));
                EtlTaskValidation.validateResumableWrapper(task, sourceConfig);
            } catch (IllegalArgumentException e) {
                throw new EtlProgramException(e.getMessage());
            }
        }
    }

    /** The source's lexical family; a missing configuration keeps the historic MySQL reading. */
    private static DatabaseType.DialectFamily scriptFamily(DatabaseConfig sourceConfig) {
        return sourceConfig == null
                ? DatabaseType.DialectFamily.MYSQL : sourceConfig.resolvedFamily();
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
     * Positions of the result columns the existing target also has, or {@code null} when the
     * run writes every result column (the table is created by this run, or its own column list
     * cannot be read).
     *
     * <p>Matching is case-insensitive: the runner writes unquoted identifiers, so the target's
     * folding rules decide how the two names meet, and a driver that reports labels upper-cased
     * must still match a lower-case alias.
     */
    private int[] keptColumnIndexes(Connection target, EtlTask task, String targetName,
                                    List<String> columnNames, boolean targetExists) {
        if (!targetExists) {
            // The table is built from the full result metadata, so nothing can be missing.
            return null;
        }
        Set<String> targetColumns = targetColumnNames(target, targetName);
        if (targetColumns == null) {
            // An INSERT-only account may not be able to describe the table. Keep the previous
            // behaviour instead of refusing a run that may work: write every result column.
            log.warn("ETL task {}: could not read the columns of target table {}; "
                    + "writing every result column", task.getId(), targetName);
            return null;
        }
        int[] kept = new int[columnNames.size()];
        int count = 0;
        for (int i = 0; i < columnNames.size(); i++) {
            if (targetColumns.contains(columnNames.get(i).toLowerCase(Locale.ROOT))) {
                kept[count++] = i;
            }
        }
        return Arrays.copyOf(kept, count);
    }

    /** The target's column labels, lower-cased; {@code null} when they cannot be read. */
    private static Set<String> targetColumnNames(Connection target, String targetName) {
        String sql = "SELECT * FROM " + targetName + " WHERE 1 = 0";
        try (Statement statement = target.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            ResultSetMetaData meta = rows.getMetaData();
            if (meta == null) {
                return null;
            }
            Set<String> names = new HashSet<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String label = meta.getColumnLabel(i);
                if (label != null) {
                    names.add(label.toLowerCase(Locale.ROOT));
                }
            }
            return names;
        } catch (SQLException e) {
            // PostgreSQL-family drivers abort their transaction on any failed statement; the
            // run continues with the old write-everything behaviour, so clear that state.
            safeRollback(target);
            log.warn("Could not read the columns of target table {}: {}", targetName,
                    e.getMessage());
            return null;
        }
    }

    private static List<String> skipped(List<String> columnNames, int[] keptIndexes) {
        boolean[] kept = new boolean[columnNames.size()];
        for (int index : keptIndexes) {
            kept[index] = true;
        }
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            if (!kept[i]) {
                skipped.add(columnNames.get(i));
            }
        }
        return skipped;
    }

    private static <T> List<T> pick(List<T> values, int[] indexes) {
        List<T> picked = new ArrayList<>(indexes.length);
        for (int index : indexes) {
            picked.add(values.get(index));
        }
        return picked;
    }

    private static Object[] pick(Object[] values, int[] indexes) {
        Object[] picked = new Object[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            picked[i] = values[indexes[i]];
        }
        return picked;
    }

    /** The source column as the metadata shape the dialects map from. */
    private static ColumnMeta columnMeta(EtlSourceColumn source, int ordinalPosition) {
        ColumnMeta column = new ColumnMeta();
        column.setName(source.name());
        column.setJdbcType(source.jdbcType());
        column.setTypeName(source.typeName());
        column.setSize(source.size());
        column.setDecimalDigits(source.decimalDigits());
        column.setNullable(source.nullable());
        column.setOrdinalPosition(ordinalPosition);
        return column;
    }

    /**
     * The JDBC type each result column's values are bound with on the target — the mapped
     * target type, not the source's. Oracle creates a CLOB for a wide VARCHAR or any
     * LONGVARCHAR/CLOB source column (and a BLOB for wide binary), and the placeholder must
     * carry that type or {@link LobBinder} never diverts a value that outgrows the driver's
     * inline ceiling.
     */
    static List<Integer> targetBindTypes(List<EtlSourceColumn> columns,
                                         DatabaseType sourceType, SqlDialect dialect) {
        List<Integer> types = new ArrayList<>(columns.size());
        for (EtlSourceColumn source : columns) {
            // Canonicalize vendor codes first: a SQL Server -157 or an Oracle code outside
            // getInternalType's table would make setNull throw.
            int canonical = VendorTypeMap.bindType(sourceType, source.jdbcType(),
                    source.typeName());
            types.add(dialect.bindJdbcType(columnMeta(source, 0), sourceType, canonical));
        }
        return types;
    }

    /**
     * Binds one extracted row to the statement through the sync writer's own value binder:
     * a value the target cannot take inline goes through {@link LobBinder} (Oracle's driver
     * binds a placeholder as a LONG once it outgrows VARCHAR2's 4000 bytes — 2000 for RAW —
     * and a CLOB column rejects that with ORA-01461; an empty string would become NULL), and
     * the vendor-specific values keep the conversions the copy path would otherwise miss:
     * a MySQL {@code BIT(n>1)} byte array decodes onto the integer column it was mapped to,
     * a Boolean becomes 1/0 for an integer target, and {@code java.time} values are
     * normalized for the target driver. A bare {@code setObject} here was the same drift the
     * read side had.
     */
    static void bindRow(PreparedStatement insert, List<Integer> targetTypes, Object[] values,
                        SqlDialect dialect, LobBinder.Tracker tracker) throws SQLException {
        for (int i = 1; i <= targetTypes.size(); i++) {
            Object value = values[i - 1];
            int type = targetTypes.get(i - 1);
            if (value == null) {
                insert.setNull(i, type);
            } else {
                JdbcRowMapper.bindTargetValue(insert, i, value, type, dialect, tracker);
            }
        }
    }

    /**
     * Queues one row's UPDATE for the emulated upsert and keeps the row's values.
     *
     * <p>The values are kept because an update count of zero is not proof that the key is
     * missing (see {@link #flushUpsertRows}), so the decision is made after the batch reports.
     *
     * <p>A key-only table has no non-key column to update; every row goes straight to the
     * probe-and-insert path.
     */
    private static void queueUpsertRow(PreparedStatement update, List<Integer> updateTypes,
                                       List<Integer> updateIndexes, Object[] values,
                                       SqlDialect dialect, LobBinder.Tracker tracker,
                                       List<Object[]> rows) throws SQLException {
        if (update != null) {
            Object[] updateValues = new Object[updateIndexes.size()];
            for (int i = 0; i < updateValues.length; i++) {
                updateValues[i] = values[updateIndexes.get(i)];
            }
            bindRow(update, updateTypes, updateValues, dialect, tracker);
            update.addBatch();
        }
        rows.add(values);
    }

    /**
     * Completes one transaction's emulated upsert: send the queued UPDATEs, then insert the
     * keys they did not reach.
     *
     * <p>A positive count means the row was updated. Anything else — zero, or
     * {@code SUCCESS_NO_INFO} from a driver that does not report counts — is decided by the
     * probe: some drivers count <em>changed</em> rows, so an UPDATE that matched an identical
     * row answers zero, and inserting then would create a second copy.
     *
     * <p>The rows are visited from the last to the first on purpose. When the source returns
     * the same key twice, sequential writes ended with the last row's values; inserting the
     * last occurrence first and letting the earlier ones find it in the probe keeps that,
     * without comparing keys in memory — Java's equality need not agree with the target's
     * (case-insensitive collations, padded CHARs, DECIMAL scales).
     */
    private static void flushUpsertRows(PreparedStatement update, PreparedStatement insert,
                                        PreparedStatement probe, List<Integer> insertTypes,
                                        List<Integer> keyTypes, List<Integer> keyIndexes,
                                        List<Object[]> rows, SqlDialect dialect,
                                        LobBinder.Tracker tracker) throws SQLException {
        int[] counts = null;
        if (update != null && !rows.isEmpty()) {
            counts = update.executeBatch();
            update.clearBatch();
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            int count = counts == null || i >= counts.length ? -1 : counts[i];
            if (count == Statement.EXECUTE_FAILED) {
                // Never reported without an exception by the drivers used here, but a
                // failed UPDATE must not fall through to a silent insert.
                throw new SQLException("the batched upsert UPDATE failed for row " + (i + 1));
            }
            if (count > 0) {
                continue;
            }
            Object[] values = rows.get(i);
            if (exists(probe, keyTypes, values, keyIndexes, dialect, tracker)) {
                continue;
            }
            bindRow(insert, insertTypes, values, dialect, tracker);
            try {
                insert.executeUpdate();
            } catch (SQLException e) {
                // A concurrent writer may have inserted this key between our probe and
                // the INSERT. Only a collision on a key the target now holds is absorbed;
                // any other integrity violation must surface instead of dropping the row.
                if (!UniqueViolations.isUniqueViolation(e)
                        || !exists(probe, keyTypes, values, keyIndexes, dialect, tracker)) {
                    throw e;
                }
            }
        }
        rows.clear();
    }

    private static boolean exists(PreparedStatement probe, List<Integer> keyTypes,
                                  Object[] values, List<Integer> keyIndexes, SqlDialect dialect,
                                  LobBinder.Tracker tracker) throws SQLException {
        Object[] keyValues = new Object[keyIndexes.size()];
        for (int i = 0; i < keyValues.length; i++) {
            keyValues[i] = values[keyIndexes.get(i)];
        }
        bindRow(probe, keyTypes, keyValues, dialect, tracker);
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
                                      List<String> primaryKeyColumns, boolean exists)
            throws SQLException {
        if (exists) {
            return false;
        }
        TableMeta table = new TableMeta();
        table.setName(configuredTable);
        for (int i = 1; i <= columns.size(); i++) {
            table.getColumns().add(columnMeta(columns.get(i - 1), i));
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
