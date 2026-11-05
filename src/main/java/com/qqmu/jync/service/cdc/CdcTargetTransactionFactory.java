package com.qqmu.jync.service.cdc;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.service.projection.redis.RedisCdcTargetTransaction;
import com.qqmu.jync.service.projection.redis.RedisProjectionWriter;
import com.qqmu.jync.service.projection.redis.RedisTargetService;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * Opens the target-side write handle for one CDC transaction, dispatching on the project's
 * target kind — the one place that knows a cache target is not a JDBC target.
 *
 * <p>One instance belongs to one consumer run: it carries that run's counters and statement
 * timeout, so the transactions it opens feed the same stats the stream handler updates.
 *
 * <p>The two-argument constructor is the JDBC-only form that hand-built consumers and their tests
 * use. A Redis project can only be served by the four-argument form the manager builds; asking a
 * JDBC-only factory for one is a wiring bug and throws rather than borrowing a JDBC connection,
 * which would write the rows nowhere and still let the consumer advance the position.
 */
public class CdcTargetTransactionFactory {

    private final CdcRuntimeStats stats;
    private final int queryTimeoutSeconds;
    private final RedisTargetService redisTargets;
    private final RedisProjectionWriter redisWriter;
    /**
     * "database.table" -> column names seen by earlier transactions of this run, HASH mode only.
     * Transaction-scoped state could never see a layout change (a DDL cannot run inside one
     * CDC transaction); this factory lives exactly as long as the run, so it can.
     */
    private final Map<String, Set<String>> hashColumnsByTable = new ConcurrentHashMap<>();

    public CdcTargetTransactionFactory(CdcRuntimeStats stats, int queryTimeoutSeconds) {
        this(stats, queryTimeoutSeconds, null, null);
    }

    public CdcTargetTransactionFactory(CdcRuntimeStats stats, int queryTimeoutSeconds,
                                       RedisTargetService redisTargets,
                                       RedisProjectionWriter redisWriter) {
        this.stats = stats;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.redisTargets = redisTargets;
        this.redisWriter = redisWriter;
    }

    public CdcTargetTransaction open(Project project, SyncContext ctx) throws SQLException {
        if (project.getTargetKindOrDefault() == SyncTargetKind.REDIS) {
            if (redisTargets == null || redisWriter == null) {
                throw new IllegalStateException("Redis CDC targets need the Redis target and"
                        + " projection beans; this factory was built for JDBC only");
            }
            return new RedisCdcTargetTransaction(ctx, redisTargets, redisWriter, stats,
                    hashColumnsByTable);
        }
        return new JdbcCdcTargetTransaction(ctx, stats, queryTimeoutSeconds);
    }
}
