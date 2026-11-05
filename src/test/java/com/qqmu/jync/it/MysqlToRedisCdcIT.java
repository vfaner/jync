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
 * MySQL 8 binlog CDC → Redis 7, end to end.
 *
 * <p>Two promises the JDBC target path cannot make for a cache are pinned here: an
 * INSERT/UPDATE/DELETE stream converges on the same keys, and a Redis outage never advances the
 * replicated position — once Redis is back, the consumer replays from where it stopped and the
 * keys converge without a baseline.
 *
 * <p>Named {@code *IT}; run explicitly with
 * {@code mvn test -Dtest=MysqlToRedisCdcIT -Dapi.version=1.44}.
 */
class MysqlToRedisCdcIT extends CdcMySqlContainerHarnessIT {

    private static final String TABLE = "cdc_cache_orders";
    private static final String PREFIX = "jync:mysql-it:";

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;
    @Autowired private RedisTargetRepository redisTargets;

    @Test
    void insertUpdateDeleteConvergeToRedis() throws Exception {
        seed();
        Project project = startedProject("mysql-redis-dml", "dml");
        long projectId = project.getId();

        RedisItSupport.waitFor("baseline keys", 60_000,
                () -> key(1) != null && key(1).contains("\"name\":\"seed-1\""));
        assertThat(key(3)).contains("\"name\":\"seed-3\"");

        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO " + TABLE + " VALUES (4, 'inserted', 4.00)");
            st.execute("UPDATE " + TABLE + " SET name = 'updated', amount = 20.00 WHERE id = 2");
            st.execute("DELETE FROM " + TABLE + " WHERE id = 3");
        }
        RedisItSupport.waitFor("stream applied", 60_000, () -> {
            String inserted = key(4);
            String updated = key(2);
            return inserted != null && inserted.contains("\"name\":\"inserted\"")
                    && updated != null && updated.contains("\"name\":\"updated\"")
                    && updated.contains("\"amount\":\"20.00\"")
                    && key(3) == null;
        });
        assertThat(positionStore.find(projectId).orElseThrow().isBaselineDone()).isTrue();

        stop(project);
    }

    @Test
    void aRedisOutageKeepsThePositionAndReplaysAfterRecovery() throws Exception {
        seed();
        Project project = startedProject("mysql-redis-outage", "outage");
        long projectId = project.getId();

        RedisItSupport.waitFor("baseline keys", 60_000,
                () -> key(1) != null && key(2) != null && key(3) != null);

        RedisItSupport.stopContainer();
        assertThat(RedisItSupport.pings()).isFalse();
        CdcPosition before = positionStore.find(projectId).orElseThrow();
        String fileBefore = before.getBinlogFile();
        Long posBefore = before.getBinlogPos();

        // Changes committed while the cache is unreachable. The consumer must fail the
        // transaction, keep the position, and retry — never skip past the rows.
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO " + TABLE + " VALUES (5, 'during-outage', 5.00)");
            st.execute("UPDATE " + TABLE + " SET name = 'changed-while-down' WHERE id = 1");
            st.execute("DELETE FROM " + TABLE + " WHERE id = 2");
        }
        Thread.sleep(3_000);
        CdcPosition still = positionStore.find(projectId).orElseThrow();
        assertThat(still.getBinlogFile()).as("binlog file must not move while Redis is down")
                .isEqualTo(fileBefore);
        assertThat(still.getBinlogPos()).as("binlog position must not move while Redis is down")
                .isEqualTo(posBefore);

        RedisItSupport.startContainer(redisTargets, project.getTargetRedisId());
        RedisItSupport.waitFor("replay after recovery", 90_000, () -> {
            String five = key(5);
            String one = key(1);
            return five != null && five.contains("\"name\":\"during-outage\"")
                    && one != null && one.contains("\"name\":\"changed-while-down\"")
                    && key(2) == null;
        });

        CdcPosition after = positionStore.find(projectId).orElseThrow();
        assertThat(after.getBinlogFile() + ":" + after.getBinlogPos())
                .as("the replay is committed and the position advances again")
                .isNotEqualTo(fileBefore + ":" + posBefore);

        stop(project);
    }

    private String key(long id) {
        return RedisItSupport.get(PREFIX + TABLE + ":" + id);
    }

    private void seed() throws SQLException {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("CREATE TABLE " + TABLE
                    + " (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2))");
            for (int i = 1; i <= 3; i++) {
                st.execute("INSERT INTO " + TABLE + " VALUES (" + i + ", 'seed-" + i + "', "
                        + i + ".00)");
            }
        }
    }

    /**
     * A fresh Redis target + an enabled BINLOG_CDC project whose target is that cache. Starts
     * the consumer and waits for the baseline gate; the caller only has to read keys.
     */
    private Project startedProject(String name, String tag) {
        RedisTarget target = RedisItSupport.newTarget(redisTargets, tag);
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
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

        manager.ensureStarted(saved);
        RedisItSupport.waitFor("baseline done", 60_000, () -> positionStore
                .find(saved.getId()).map(CdcPosition::isBaselineDone).orElse(false));
        return saved;
    }

    private void stop(Project project) {
        manager.ensureStopped(project.getId());
        disableProject(project);
    }
}
