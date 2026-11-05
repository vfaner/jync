package com.qqmu.jync.service.projection.redis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.service.cdc.CdcBaselineAnchor;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.ColumnCleanser;
import com.qqmu.jync.service.sync.JdbcRowMapper;
import com.qqmu.jync.service.sync.KeysetChunker;
import com.qqmu.jync.service.sync.LockLostException;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * The first full projection into a Redis target: record the capture anchor, page every selected
 * table by primary key and write each page as one pipeline, then mark the baseline done.
 *
 * <p>Order matters. The anchor is recorded before the first read, exactly like the JDBC baseline
 * records its position before its full load, so changes made during the copy are replayed from a
 * point that precedes them. {@code baseline_done} flips only after every table was written: a
 * crash mid-copy leaves it false and the whole projection reruns, which is safe because every
 * mutation is an idempotent upsert or delete.
 *
 * <p>A table without a primary key cannot be keyed, so it is skipped with a warning rather than
 * loaded under invented keys; its rows stay in the database and readers fall back to it.
 */
@Slf4j
@Service
public class RedisBaselineService {

    private static final int DEFAULT_PAGE_SIZE = 1_000;

    private final DataSourceManager dataSourceManager;
    private final RedisTargetService targets;
    private final RedisProjectionWriter writer;
    private final CdcPositionStore positionStore;
    private final SyncStateWriter stateWriter;
    private final SyncTaskStore taskStore;

    public RedisBaselineService(DataSourceManager dataSourceManager, RedisTargetService targets,
                                RedisProjectionWriter writer, CdcPositionStore positionStore,
                                SyncStateWriter stateWriter, SyncTaskStore taskStore) {
        this.dataSourceManager = dataSourceManager;
        this.targets = targets;
        this.writer = writer;
        this.positionStore = positionStore;
        this.stateWriter = stateWriter;
        this.taskStore = taskStore;
    }

    /**
     * @return how many tables were projected and how many mutations were written; a one-click
     *         rebuild reports both back to the operator
     */
    public BaselineResult run(SyncContext ctx, BooleanSupplier lockRenewer,
                              CdcBaselineAnchor anchor) {
        Project project = ctx.getProject();
        long projectId = project.getId();
        RedisProjectionConfig projection = requireProjection(ctx);
        RedisTarget target = requireTarget(project);
        RedisKeyBuilder keys = new RedisKeyBuilder(projection.getKeyPrefix());
        RedisRowSerializer serializer = serializer(projection);
        int pageSize = ctx.getBatchSize() > 0 ? ctx.getBatchSize() : DEFAULT_PAGE_SIZE;
        long startedAt = System.currentTimeMillis();

        // Before the first read, so every change made while the copy runs resumes after it.
        anchor.record(ctx);

        long written = 0;
        int projected = 0;
        try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
            DatabaseMeta meta = ctx.getSourceReader().readAllForData(conn, ctx.getSourceSchema(),
                    null, null, null, false, false);
            for (TableMeta table : tables(meta)) {
                if (!ctx.getConfig().includesTable(table.getName())) {
                    continue;
                }
                if (table.getPrimaryKeys() == null || table.getPrimaryKeys().isEmpty()) {
                    log.warn("Redis baseline skips table {} of project {}: it has no primary key to"
                            + " build cache keys from; its rows stay in the database",
                            table.getName(), projectId);
                    continue;
                }
                written += projectTable(ctx, conn, target, table, keys, serializer, pageSize,
                        lockRenewer);
                projected++;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Redis baseline read failed: " + e.getMessage(), e);
        }

        positionStore.markBaselineDone(projectId, RedisProjectionSignature.of(projection));
        stateWriter.recordLog(projectId, ObjectType.PROJECT, null, ChangeType.INFO,
                "Redis baseline projected " + written + " row(s) into the cache",
                true, System.currentTimeMillis() - startedAt, (int) written);
        try {
            taskStore.markCdcDataApplied(projectId);
        } catch (RuntimeException e) {
            log.warn("Could not advance last-sync after the Redis baseline of project {}: {}",
                    projectId, e.getMessage());
        }
        log.info("Redis baseline for project {} completed: {} table(s), {} row(s) projected",
                projectId, projected, written);
        return new BaselineResult(projected, written);
    }

    /** Tables projected and mutations written by one baseline run. */
    public record BaselineResult(int tables, long rows) {
    }

    private long projectTable(SyncContext ctx, Connection conn, RedisTarget target,
                              TableMeta table, RedisKeyBuilder keys,
                              RedisRowSerializer serializer, int pageSize,
                              BooleanSupplier lockRenewer) throws SQLException {
        String sourceName = table.getName();
        String targetName = ctx.getConfig().targetTableName(sourceName);
        List<String> pkColumns = table.getPrimaryKeys();
        ColumnCleanser cleanser = ColumnCleanser.forTable(ctx.getConfig(), sourceName);
        List<Object> lastPk = null;
        long written = 0;

        while (true) {
            List<Object> params = new ArrayList<>();
            String sql = KeysetChunker.pageSql(ctx.getSourceDialect(), ctx.getSourceSchema(),
                    sourceName, pkColumns, lastPk, null, null, null, pageSize, params);
            List<RedisMutation> page = new ArrayList<>(pageSize);
            List<Object> lastRawKey = null;
            int rowsRead = 0;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    JdbcRowMapper.bindParameter(ps, i + 1, params.get(i), ctx.getSourceDialect());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<String> columns = JdbcRowMapper.columnNames(md);
                    List<Integer> types = JdbcRowMapper.columnTypes(md);
                    List<String> typeNames = JdbcRowMapper.columnTypeNames(md);
                    while (rs.next()) {
                        Map<String, Object> row = JdbcRowMapper.readRow(rs, columns, types,
                                typeNames, ctx.getSourceType());
                        // The next page's keyset predicate binds against the RAW source keys,
                        // so the boundary is captured before the cleanser rewrites them. A
                        // rule on a key column (LOWER on an uppercase key, say) otherwise
                        // advances the cursor to a value the source ordering never reaches,
                        // silently skipping every remaining row.
                        lastRawKey = keyValues(pkColumns, row);
                        cleanser.apply(row);
                        rowsRead++;
                        RedisMutation mutation = mutation(keys, serializer, targetName,
                                pkColumns, row);
                        if (mutation != null) {
                            page.add(mutation);
                        }
                    }
                }
            }

            if (!page.isEmpty()) {
                writer.apply(target, page);
                written += page.size();
            }
            if (rowsRead < pageSize) {
                return written;
            }
            // A full page: the next one starts after its last row's raw key. A row skipped
            // above still advances the cursor, or the page would be read again forever.
            lastPk = lastRawKey;
            if (!lockRenewer.getAsBoolean()) {
                throw new LockLostException("Redis baseline of project "
                        + ctx.getProject().getId() + " lost its lease mid-copy; it stays not-done"
                        + " and the instance that took over reruns it");
            }
        }
    }

    private static RedisMutation mutation(RedisKeyBuilder keys, RedisRowSerializer serializer,
                                          String table, List<String> pkColumns,
                                          Map<String, Object> row) {
        String key;
        try {
            key = keys.key(table, pkColumns, row);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping a row in the Redis baseline: {}", e.getMessage());
            return null;
        }
        RedisMutation mutation;
        try {
            mutation = serializer.upsert(key, row);
        } catch (RuntimeException e) {
            log.warn("Skipping row {} in the Redis baseline: {}", key, e.getMessage());
            return null;
        }
        if (mutation == null) {
            log.warn("Skipping row {} in the Redis baseline: its value could not be projected"
                    + " within the configured limits", key);
        }
        return mutation;
    }

    private static List<Object> keyValues(List<String> pkColumns, Map<String, Object> row) {
        List<Object> values = new ArrayList<>(pkColumns.size());
        for (String column : pkColumns) {
            values.add(valueIgnoreCase(row, column));
        }
        return values;
    }

    private static Object valueIgnoreCase(Map<String, Object> row, String column) {
        if (row == null) {
            return null;
        }
        Object direct = row.get(column);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static List<TableMeta> tables(DatabaseMeta meta) {
        if (meta == null || meta.getTables() == null) {
            return List.of();
        }
        return meta.getTables();
    }

    private static RedisRowSerializer serializer(RedisProjectionConfig projection) {
        return projection.getValueFormat() == RedisValueFormat.HASH
                ? new RedisRowSerializerHash(projection.getExcludedColumns(),
                        projection.getMaxValueBytes(), projection.getTtlSeconds())
                : new RedisRowSerializerJson(projection.getExcludedColumns(),
                        projection.getMaxValueBytes(), projection.getTtlSeconds());
    }

    private RedisTarget requireTarget(Project project) {
        Long targetId = project == null ? null : project.getTargetRedisId();
        if (targetId == null) {
            throw new IllegalStateException("Redis target is not configured for project "
                    + (project == null ? "?" : project.getId()));
        }
        Optional<RedisTarget> found = targets.find(targetId);
        if (found.isEmpty()) {
            throw new IllegalStateException("Redis target " + targetId + " no longer exists");
        }
        return found.get();
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
