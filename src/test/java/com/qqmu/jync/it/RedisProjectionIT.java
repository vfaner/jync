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
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisRebuildService;

/**
 * The whole cache lifecycle against a real Redis 7: baseline projection, overwrite on a second
 * rebuild, delete, and a prefix sweep that must not touch a neighbouring project's keys.
 *
 * <p>Runs a MySQL 8 source (the shared binlog container of
 * {@link CdcMySqlContainerHarnessIT}) into a {@link RedisItSupport} container. Every value is
 * read back with a raw Redis command, so the assertion is on the bytes the cache actually holds,
 * not on Jync's serializer. Named {@code *IT}: run explicitly with
 * {@code mvn test -Dtest=RedisProjectionIT -Dapi.version=1.44}.
 */
class RedisProjectionIT extends CdcMySqlContainerHarnessIT {

    private static final String TABLE = "proj_orders";
    private static final String PREFIX = "jync:proj-it:";

    @Autowired private RedisTargetRepository redisTargets;
    @Autowired private RedisRebuildService rebuild;
    @Autowired private CdcPositionStore positionStore;

    @Test
    void baselineRebuildsOverwritesDeletesAndSweepsOnlyTheProjectPrefix() throws Exception {
        seed();
        RedisTarget target = RedisItSupport.newTarget(redisTargets, "projection");
        Project project = newRedisProject("redis-projection-it", target);

        RedisRebuildService.RebuildResult first = rebuild.rebuild(project.getId(), false);
        assertThat(first.tables()).isEqualTo(1);
        assertThat(first.rows()).isEqualTo(3);
        assertThat(key(1)).as("first row of the baseline").contains("\"id\":1")
                .contains("\"name\":\"seed-1\"").contains("\"amount\":\"1.50\"");
        assertThat(key(3)).contains("\"name\":\"seed-3\"");
        assertThat(positionStore.find(project.getId()).orElseThrow().isBaselineDone()).isTrue();

        // A second rebuild re-projects the source as it is now: UPDATE overwrites, INSERT adds.
        // A row deleted at the source has no row left to project, so its key survives — the
        // documented orphan-key gap of a cache that only writes rows that exist (§10.2).
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("UPDATE " + TABLE + " SET name = 'updated', amount = 9.00 WHERE id = 2");
            st.execute("INSERT INTO " + TABLE + " VALUES (4, 'appended', 4.00)");
            st.execute("DELETE FROM " + TABLE + " WHERE id = 3");
        }
        rebuild.rebuild(project.getId(), false);
        assertThat(key(2)).contains("\"name\":\"updated\"").contains("\"amount\":\"9.00\"");
        assertThat(key(4)).contains("\"name\":\"appended\"");
        assertThat(key(3)).as("a rebuild without sweep only overwrites; the orphan stays")
                .contains("\"name\":\"seed-3\"");

        // The sweep may only remove keys under this project's prefix. A same-prefix orphan goes;
        // a sibling project whose prefix merely starts with the same characters stays.
        RedisItSupport.set(PREFIX + "ghost", "orphan");
        RedisItSupport.set("jync:proj-it-other:keep", "neighbour");
        RedisItSupport.set("jync:proj-it2:keep", "neighbour-2");

        RedisRebuildService.RebuildResult swept = rebuild.rebuild(project.getId(), true);
        assertThat(swept.sweptKeys()).as("4 row keys (one orphaned) + the planted orphan")
                .isEqualTo(5);
        assertThat(RedisItSupport.get(PREFIX + "ghost")).isNull();
        assertThat(key(3)).as("the sweep is what removes the key of a source-deleted row").isNull();
        assertThat(RedisItSupport.get("jync:proj-it-other:keep")).isEqualTo("neighbour");
        assertThat(RedisItSupport.get("jync:proj-it2:keep")).isEqualTo("neighbour-2");
        assertThat(key(1)).as("the sweep is followed by a fresh baseline").isNotNull();
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
                        + i + ".50)");
            }
        }
    }

    private Project newRedisProject(String name, RedisTarget target) {
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
        project.setEnabled(false);              // the rebuild of a running project is refused
        Project saved = projectRepository.save(project);
        taskStore.ensureTask(saved);
        return saved;
    }
}
