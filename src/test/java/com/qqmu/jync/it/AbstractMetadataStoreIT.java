package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.repository.AppUserRepository;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.OaAuthConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncTaskRepository;
import com.qqmu.jync.service.auth.AppUserService;

/**
 * 元数据库跑在真实数据库上时的契约测试。
 *
 * <p>内置 H2 之外的数据库从未被这个应用真正启动验证过：打包的 {@code application.yml}
 * 指向 H2，仓库里也没有任何测试把整朵应用接到 MySQL/Oracle 上。这里让 Hibernate 在容器里
 * 用 {@code ddl-auto: update} 建表，再走一遍有代表性的读写路径，钉住四件容易随方言漂移的
 * 事：建表、自增主键、{@code @Lob} 往返、批量 update/delete 与聚合查询。
 *
 * <p>子类负责容器与数据源属性；本类只描述“换库之后必须仍然成立”的行为。
 */
@SpringBootTest(properties = {
        "sync.ai.enabled=false",
        "app.github-url=https://example.com/repo"
})
abstract class AbstractMetadataStoreIT {

    /** 实体映射出来的全部表；少一张就是建表阶段静默失败。 */
    private static final Set<String> EXPECTED_TABLES = Set.of(
            "ai_provider", "app_user", "audit_event", "cdc_position", "change_log",
            "chunk_checkpoint", "database_config", "metadata_snapshot", "oa_auth_config",
            "project", "sync_progress", "sync_task");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AppUserRepository appUsers;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private ChangeLogRepository changeLogs;

    @Autowired
    private SyncTaskRepository tasks;

    @Autowired
    private OaAuthConfigRepository oaConfigs;

    @Test
    void hibernateCreatesEveryTable() throws Exception {
        Set<String> actual = new HashSet<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(conn.getCatalog(), null, "%",
                    new String[] {"TABLE"})) {
                while (rs.next()) {
                    actual.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        assertThat(actual).as("Hibernate 必须按实体建出全部元数据表").containsAll(EXPECTED_TABLES);
    }

    @Test
    void theSeededAccountsAreThereWithoutAnyManualStep() {
        assertThat(appUsers.findByUsername(AppUserService.ADMIN_USERNAME))
                .as("空库首次启动必须自动种入管理员").isPresent();
        assertThat(appUsers.findByUsername(AppUserService.VIEWER_USERNAME))
                .as("空库首次启动必须自动种入访客").isPresent();
    }

    /** 批量 update/delete 与生产代码一样必须运行在事务里；否则 JPQL 的 flush 无处可落。 */
    @Test
    @Transactional
    void identityLobsAndBulkQueriesRoundTrip() {
        String big = "lob-".repeat(16 * 1024);
        Project project = new Project();
        project.setName("metadata-store-it-" + System.nanoTime());
        project.setSyncConfig(big);
        project.setEnabled(false);
        projects.saveAndFlush(project);
        assertThat(project.getId()).as("IDENTITY 自增必须回填主键").isNotNull();

        Project stored = projects.findById(project.getId()).orElseThrow();
        assertThat(stored.getSyncConfig()).as("@Lob 字段必须原样往返").isEqualTo(big);
        assertThat(stored.getCreatedAt()).as("@PrePersist 必须写入时间戳").isNotNull();

        ChangeLog entry = new ChangeLog();
        entry.setProjectId(project.getId());
        entry.setObjectType(ObjectType.TABLE);
        entry.setObjectName("t1");
        entry.setChangeType(ChangeType.INSERT);
        entry.setAffectedRows(7);
        entry.setDetails("detail-".repeat(1024));
        entry.setSuccess(true);
        entry.setOccurredAt(Instant.now());
        changeLogs.saveAndFlush(entry);

        List<Object[]> grouped = changeLogs.sumAffectedRowsGroupedByProject();
        assertThat(grouped).anySatisfy(row -> {
            assertThat(((Number) row[0]).longValue()).isEqualTo(project.getId());
            assertThat(((Number) row[1]).longValue()).isEqualTo(7L);
        });
        assertThat(changeLogs.sumAffectedRowsSince(Instant.now().minusSeconds(60))).isEqualTo(7L);

        SyncTask task = new SyncTask();
        task.setProjectId(project.getId());
        tasks.saveAndFlush(task);

        Instant now = Instant.now();
        Instant expires = now.plusSeconds(300);
        assertThat(tasks.acquireLock(project.getId(), "node-a", expires, now))
                .as("无人持锁时第一次抢占必须成功").isEqualTo(1);
        assertThat(tasks.acquireLock(project.getId(), "node-b", expires, now))
                .as("同一把未过期的锁不能被第二个节点抢走").isZero();
        assertThat(tasks.renewLock(project.getId(), "node-b", expires))
                .as("非持有者续约必须影响 0 行").isZero();
        assertThat(tasks.renewLock(project.getId(), "node-a", expires.plusSeconds(60)))
                .as("持有者续约必须影响 1 行").isEqualTo(1);
        assertThat(tasks.findByProjectId(project.getId()).orElseThrow().getLockOwner())
                .isEqualTo("node-a");

        assertThat(tasks.releaseLock(project.getId(), "node-b")).isZero();
        assertThat(tasks.releaseLock(project.getId(), "node-a")).isEqualTo(1);

        assertThat(changeLogs.deleteByProjectId(project.getId())).isEqualTo(1);
        tasks.deleteByProjectId(project.getId());
        projects.deleteById(project.getId());
    }

    @Test
    void theSingleSignInConfigRowStoresItsMode() {
        OaAuthConfig config = new OaAuthConfig();
        config.setId(1L);
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.TICKET);
        config.setLocalLoginEnabled(true);
        oaConfigs.saveAndFlush(config);

        assertThat(oaConfigs.findById(1L).orElseThrow().getMode())
                .as("mode 列在 Oracle 族是保留字，必须能在真实库上写入并读回")
                .isEqualTo(OaAuthConfig.Mode.TICKET);
    }
}
