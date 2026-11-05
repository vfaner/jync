package com.qqmu.jync.service.projection.redis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.cdc.CdcSchemaDrift;
import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.cdc.CdcTargetTransaction;
import com.qqmu.jync.service.sync.ColumnCleanser;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * The Redis side of one CDC transaction: buffer every row's mutation, send the buffer as pipelined
 * chunks only when it grows past the configured cap, and publish counters only after the last
 * chunk succeeded.
 *
 * <p>No {@code MULTI/EXEC} (see {@link RedisProjectionWriter}): a chunk that fails throws, the
 * consumer closes this transaction without advancing its position, and replay converges because
 * every command is idempotent. The one value the cache cannot repair by replay is a HASH field of
 * a dropped column, so a layout change in HASH mode parks the project instead.
 *
 * <p>Nothing here changes the JDBC path: this class implements the same {@link CdcTargetTransaction}
 * contract and is created by the factory only for a project whose target kind is Redis.
 */
public class RedisCdcTargetTransaction implements CdcTargetTransaction {

    private static final Logger log = LoggerFactory.getLogger(RedisCdcTargetTransaction.class);

    private final SyncContext ctx;
    private final RedisTarget target;
    private final RedisProjectionWriter writer;
    private final CdcRuntimeStats stats;
    private final RedisProjectionConfig projection;
    private final RedisKeyBuilder keys;
    private final RedisRowSerializer serializer;
    private final int maxCommandsPerTransaction;

    /** Table name -> cleanser; the rules are static for the run, so one instance per table suffices. */
    private final Map<String, ColumnCleanser> cleansers = new HashMap<>();
    /**
     * "database.table" -> the column names last seen, HASH mode only. Owned by the factory and
     * therefore shared by every transaction of one consumer run: a DDL cannot happen inside a
     * single transaction, so per-instance state could never observe a change.
     */
    private final Map<String, Set<String>> hashColumnsByTable;
    private final Map<String, Long> upsertsByTable = new LinkedHashMap<>();
    private final Map<String, Long> deletesByTable = new LinkedHashMap<>();

    private final List<RedisMutation> buffer = new ArrayList<>();
    private int bufferedCommands;
    private long skippedRows;
    private boolean closed;

    public RedisCdcTargetTransaction(SyncContext ctx, RedisTargetService targets,
                                     RedisProjectionWriter writer, CdcRuntimeStats stats) {
        this(ctx, targets, writer, stats, new ConcurrentHashMap<>());
    }

    /**
     * @param hashColumnsByTable the run-scoped column history; the factory passes the same map
     *                           to every transaction it opens, so a mid-run DDL is visible
     */
    public RedisCdcTargetTransaction(SyncContext ctx, RedisTargetService targets,
                                     RedisProjectionWriter writer, CdcRuntimeStats stats,
                                     Map<String, Set<String>> hashColumnsByTable) {
        this.ctx = ctx;
        this.hashColumnsByTable = hashColumnsByTable;
        this.writer = writer;
        this.stats = stats;
        this.target = resolveTarget(ctx, targets);
        this.projection = requireProjection(ctx);
        this.keys = new RedisKeyBuilder(projection.getKeyPrefix());
        this.serializer = projection.getValueFormat() == RedisValueFormat.HASH
                ? new RedisRowSerializerHash(projection.getExcludedColumns(),
                        projection.getMaxValueBytes(), projection.getTtlSeconds())
                : new RedisRowSerializerJson(projection.getExcludedColumns(),
                        projection.getMaxValueBytes(), projection.getTtlSeconds());
        this.maxCommandsPerTransaction = projection.getMaxCommandsPerTransaction();
    }

    @Override
    public void applyUpserts(CdcTableBinding binding, List<Map<String, Object>> rows) {
        apply(binding, rows, true);
    }

    @Override
    public void applyDeletes(CdcTableBinding binding, List<Map<String, Object>> rows) {
        apply(binding, rows, false);
    }

    @Override
    public void commit() {
        ensureOpen();
        flush();
        upsertsByTable.forEach(stats::addTableUpserts);
        deletesByTable.forEach(stats::addTableDeletes);
        if (skippedRows > 0) {
            log.warn("Redis projection skipped {} row(s) that could not be keyed or serialized;"
                    + " the stream continues and those keys fall back to the database", skippedRows);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // A Redis pipeline has no rollback: chunks already sent stay. The position was not
        // advanced, so replay rewrites them; dropping the rest is what keeps an aborted
        // transaction from leaking into the next one.
        buffer.clear();
        bufferedCommands = 0;
    }

    private void apply(CdcTableBinding binding, List<Map<String, Object>> rows, boolean upsert) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        ensureOpen();
        requireStableLayout(binding);
        ColumnCleanser cleanser = cleansers.computeIfAbsent(binding.getTable(),
                table -> ColumnCleanser.forTable(ctx.getConfig(), table));
        long accepted = 0;
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Map<String, Object> cleansed = cleanser.apply(row);
            String key = keyFor(binding, cleansed);
            if (key == null) {
                skippedRows++;
                continue;
            }
            RedisMutation mutation = upsert
                    ? serializer.upsert(key, cleansed)
                    : serializer.delete(key);
            if (mutation == null) {
                skippedRows++;
                continue;
            }
            buffer.add(mutation);
            bufferedCommands += commands(mutation);
            accepted++;
            if (maxCommandsPerTransaction > 0 && bufferedCommands >= maxCommandsPerTransaction) {
                flush();
            }
        }
        if (accepted > 0) {
            Map<String, Long> tally = upsert ? upsertsByTable : deletesByTable;
            tally.merge(binding.getTable(), accepted, Long::sum);
        }
    }

    /**
     * Sends the buffered chunk and clears it. Clearing first means a failed chunk is not resent by
     * {@link #close()} or a later flush: the transaction has failed as a whole and the consumer
     * discards it.
     */
    private void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<RedisMutation> chunk = List.copyOf(buffer);
        buffer.clear();
        bufferedCommands = 0;
        writer.apply(target, chunk);
    }

    /**
     * A HASH write only touches the fields present in this row, so a column that vanished from the
     * source would leave its old field in Redis forever. JSON rewrites the whole value and needs no
     * check.
     *
     * <p>Compared by column name, not by the binding's layout signature: an added column is
     * harmless — the next write simply stores the new field — and parking on it would take a
     * healthy projection down for nothing. Only a name seen earlier and now missing (a drop or a
     * rename) leaves a field the projection cannot delete.
     */
    private void requireStableLayout(CdcTableBinding binding) {
        if (projection.getValueFormat() != RedisValueFormat.HASH) {
            return;
        }
        if (!binding.isApplicable()) {
            return;                      // no PK: its rows are never decoded, so nothing is written
        }
        List<String> names = binding.columnNames();
        if (names == null || names.isEmpty()) {
            return;
        }
        Set<String> current = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        current.addAll(names);
        String qualified = binding.getDatabase() + "." + binding.getTable();
        Set<String> previous = hashColumnsByTable.put(qualified,
                Collections.unmodifiableSet(current));
        if (previous == null) {
            return;
        }
        Set<String> vanished = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        vanished.addAll(previous);
        vanished.removeAll(current);
        if (!vanished.isEmpty()) {
            throw new RedisLayoutDriftException(CdcSchemaDrift.MARKER + ": table " + qualified
                    + " no longer has column(s) " + vanished + " in its HASH projection; their"
                    + " fields would stay in Redis forever — rebuild the projection"
                    + " (re-baseline) to clear them");
        }
    }

    /** Null when the row cannot be keyed (a missing primary-key component or unsupported type). */
    private String keyFor(CdcTableBinding binding, Map<String, Object> row) {
        try {
            return keys.key(binding.getTargetTable(), binding.pkNames(), row);
        } catch (IllegalArgumentException e) {
            log.debug("Redis projection skipped a row of {}: {}", binding.getTable(), e.getMessage());
            return null;
        }
    }

    /** The number of real commands a mutation turns into, for the per-transaction budget. */
    private static int commands(RedisMutation mutation) {
        return switch (mutation.kind()) {
            case DEL -> 1;
            case SET -> 1 + (mutation.hasTtl() ? 1 : 0);
            case HSET -> (mutation.fields().isEmpty() ? 0 : 1)
                    + (mutation.deleteFields().isEmpty() ? 0 : 1)
                    + (mutation.hasTtl() ? 1 : 0);
        };
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Redis projection transaction is already closed");
        }
    }

    private static RedisTarget resolveTarget(SyncContext ctx, RedisTargetService targets) {
        Project project = ctx.getProject();
        Long targetId = project == null ? null : project.getTargetRedisId();
        if (targetId == null) {
            throw new IllegalStateException("Redis target is not configured for project "
                    + (project == null ? "?" : project.getId()));
        }
        return targets.find(targetId).orElseThrow(() -> new IllegalStateException(
                "Redis target " + targetId + " no longer exists"));
    }

    private static RedisProjectionConfig requireProjection(SyncContext ctx) {
        RedisProjectionConfig projection = ctx.getConfig() == null
                ? null : ctx.getConfig().getRedisProjection();
        if (projection == null) {
            throw new IllegalStateException("Redis projection settings are missing for project "
                    + (ctx.getProject() == null ? "?" : ctx.getProject().getId()));
        }
        return projection;
    }
}
