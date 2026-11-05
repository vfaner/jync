package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * PostgreSQL 16 logical replication → Redis 7, end to end.
 *
 * <p>The PG consumer creates its slot before the baseline, so a Redis target in the slot stream
 * must see the same INSERT/UPDATE/DELETE convergence the JDBC target family already proves.
 * Values are read with raw Redis commands.
 *
 * <p>Named {@code *IT}; run explicitly with
 * {@code mvn test -Dtest=PgToRedisCdcIT -Dapi.version=1.44}.
 */
class PgToRedisCdcIT extends PgCdcHarnessIT {

    private static final String TABLE = "pg_redis_orders";
    private static final String PREFIX = "jync:pg-it:";

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;
    @Autowired private RedisTargetRepository redisTargets;

    @Test
    void insertUpdateDeleteConvergeToRedis() throws Exception {
        seed();
        RedisTarget target = RedisItSupport.newTarget(redisTargets, "pg");
        Project project = newRedisProject("pg-redis-it", target);

        manager.ensureStarted(project);
        RedisItSupport.waitFor("baseline done", 60_000, () -> positionStore
                .find(project.getId()).map(CdcPosition::isBaselineDone).orElse(false));
        RedisItSupport.waitFor("baseline keys", 60_000,
                () -> key(1) != null && key(1).contains("\"name\":\"seed-1\""));
        assertThat(key(3)).contains("\"name\":\"seed-3\"");

        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO public." + TABLE + " VALUES (4, 'inserted', 4.00)");
            st.execute("UPDATE public." + TABLE + " SET name = 'updated' WHERE id = 2");
            st.execute("DELETE FROM public." + TABLE + " WHERE id = 3");
        }
        RedisItSupport.waitFor("stream applied", 60_000, () -> {
            String inserted = key(4);
            String updated = key(2);
            return inserted != null && inserted.contains("\"name\":\"inserted\"")
                    && updated != null && updated.contains("\"name\":\"updated\"")
                    && key(3) == null;
        });

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    private String key(long id) {
        return RedisItSupport.get(PREFIX + TABLE + ":" + id);
    }

    private void seed() throws SQLException {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS public." + TABLE);
            st.execute("CREATE TABLE public." + TABLE
                    + " (id BIGINT PRIMARY KEY, name VARCHAR(64), amount NUMERIC(10,2))");
            for (int i = 1; i <= 3; i++) {
                st.execute("INSERT INTO public." + TABLE + " VALUES (" + i + ", 'seed-" + i
                        + "', " + i + ".00)");
            }
        }
    }

    private Project newRedisProject(String name, RedisTarget target) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.PG_LOGICAL_CDC);
        config.getTables().add(TABLE);
        RedisProjectionConfig projection = new RedisProjectionConfig();
        projection.setKeyPrefix(PREFIX);
        config.setRedisProjection(projection);

        Project project = new Project();
        project.setName(name);
        project.setSourceDbId(sourceConfig().getId());
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(target.getId());
        project.setSyncConfig(contextFactory.serializeConfig(config));
        project.setEnabled(true);
        Project saved = projectRepository.save(project);
        taskStore.ensureTask(saved);
        return saved;
    }
}
