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
import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.auth.AppUserService;
import com.qqmu.jync.service.metrics.ChartSeries;
import com.qqmu.jync.service.metrics.MetricsQueryService;
import com.qqmu.jync.service.metrics.MetricsRange;
import com.qqmu.jync.service.metrics.ProjectMetricsView;
import com.qqmu.jync.service.metrics.SeriesPoint;
import com.qqmu.jync.service.sync.SyncStateWriter;

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
            "project", "sync_progress", "sync_task", "system_setting");

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

    @Autowired
    private DashboardService dashboard;

    @Autowired
    private MetricsQueryService metrics;

    @Autowired
    private SyncStateWriter stateWriter;

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
        assertThat(changeLogs.sumAffectedRowsBetween(
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60))).isEqualTo(7L);

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

    /**
     * 跳过 DDL 的登记、去重、待办查询与清理必须能在真实元数据库上执行。
     *
     * <p>{@code details} 是 {@code @Lob}：Oracle 族建成 CLOB，用 {@code =} 比较会报
     * ORA-00932；PostgreSQL 把无界的 {@code @Lob String} 建成 {@code oid}，{@code =} 每次
     * 绑定都是一个新的大对象（静默查不到），{@code like} 更是直接报
     * {@code operator does not exist: oid ~~ text}。而这些 SQL 正是 CDC 读到一条 DDL 时
     * 无条件执行的——查询一失败，项目就会被当成流故障停摆。H2 的兼容模式不会暴露这一点，
     * 只有真库才会。这里走生产入口：登记（含重启后的同位置去重、LogMiner 的文本去重）、
     * 待办判断、待办列表与清理。
     */
    @Test
    @Transactional
    void skippedDdlTaskLifecycleRunsOnTheMetadataDatabase() {
        long projectId = 4242L;
        String statement = "ALTER TABLE t ADD COLUMN c INT";

        // 先登记、后断言：MySQL/MariaDB 的测试事务是 REPEATABLE READ，而 recordSkippedDdl
        // 走 REQUIRES_NEW，本事务第一次读之前提交的行才在快照里。
        //
        // 重启后从没推进过的位点重新读到同一个事件：只算一件任务。
        stateWriter.recordSkippedDdl(projectId, "t", statement, "binlog.000001", 100L);
        stateWriter.recordSkippedDdl(projectId, "t", statement, "binlog.000001", 100L);
        // 同一语句出现在另一个位点：一个新事件，一件新任务。
        stateWriter.recordSkippedDdl(projectId, "t", statement, "binlog.000001", 200L);
        // LogMiner 没有位点：同一条语句只登记一次。
        long positionlessProject = 4243L;
        stateWriter.recordSkippedDdl(positionlessProject, "T", statement);
        stateWriter.recordSkippedDdl(positionlessProject, "T", statement);
        // 普通失败行：清理历史时可以删，DDL 待办必须留下。
        ChangeLog failure = ChangeLog.of(projectId, ObjectType.TABLE, "t", ChangeType.ERROR,
                "connection refused");
        failure.setSuccess(false);
        changeLogs.saveAndFlush(failure);

        assertThat(stateWriter.hasOpenDdlTask(projectId))
                .as("CDC 建流时无条件调用，元数据库上必须能执行").isTrue();
        assertThat(changeLogs.countOpenDdlTasks(projectId)).isEqualTo(2);
        assertThat(changeLogs.findOpenDdlTasks(projectId)).hasSize(2);
        assertThat(changeLogs.countOpenDdlTasks(positionlessProject)).isEqualTo(1);

        assertThat(changeLogs.deleteByProjectIdKeepingOpenDdlTasks(projectId))
                .as("只删普通失败行").isEqualTo(1);
        assertThat(changeLogs.findOpenDdlTasks(projectId)).hasSize(2);

        // 处理完之后才随历史一起清掉。
        for (ChangeLog open : changeLogs.findOpenDdlTasks(projectId)) {
            open.setResolutionStatus(ChangeLog.RESOLUTION_PROCESSED);
        }
        changeLogs.flush();
        assertThat(changeLogs.deleteByProjectIdKeepingOpenDdlTasks(projectId))
                .as("已处理的 DDL 行只是历史").isEqualTo(2);
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

    /**
     * 看板趋势图与性能页的变更曲线必须能在真实元数据库上分桶。
     *
     * <p>这两条查询曾经是原生 SQL 里的 {@code date_trunc('hour'|'minute', ...)}：H2 和
     * PostgreSQL 有，MySQL 没有。顾客把元数据库迁到 MySQL 之后，打开项目性能页
     * （{@code GET /projects/{id}/metrics?range=24h}）直接 500：
     * {@code FUNCTION jync.date_trunc does not exist}。这里在每种真实元数据库上调用同一对
     * 服务入口（看板 24 小时趋势、项目 1 小时变更曲线），钉住「时间分桶与方言无关」。
     */
    @Test
    @Transactional
    void theChangeLogChartsAggregateOnTheMetadataDatabase() {
        changeLogs.deleteAllBulk();

        long projectId = 777_0001L;
        Instant now = Instant.now();
        chartEntry(projectId, ChangeType.INSERT, now.minusSeconds(90), 3);
        chartEntry(projectId, ChangeType.INSERT, now.minusSeconds(80), 4);
        chartEntry(projectId, ChangeType.UPDATE, now.minusSeconds(70), 1);
        // 另一个项目：看板的 24 小时总量包含它，项目曲线必须排除它。
        chartEntry(projectId + 1, ChangeType.DELETE, now.minusSeconds(60), 2);

        DashboardService.MultiSeriesActivity chart = dashboard.activitySeries();
        assertThat(chart.totalSyncChanges())
                .as("看板趋势图必须按小时在元数据库里分组")
                .isEqualTo(4L);
        assertThat(chart.totalSyncRows()).isEqualTo(10L);

        ProjectMetricsView view = metrics.project(projectId, MetricsRange.LAST_HOUR);
        assertThat(chartTotal(view, "insert"))
                .as("项目曲线的每分钟 INSERT 计数")
                .isEqualTo(2.0);
        assertThat(chartTotal(view, "update")).isEqualTo(1.0);
        assertThat(chartTotal(view, "delete")).isZero();
    }

    private void chartEntry(long projectId, ChangeType type, Instant occurredAt, int rows) {
        ChangeLog entry = new ChangeLog();
        entry.setProjectId(projectId);
        entry.setObjectType(ObjectType.TABLE);
        entry.setObjectName("chart_t");
        entry.setChangeType(type);
        entry.setAffectedRows(rows);
        entry.setSuccess(true);
        entry.setOccurredAt(occurredAt);
        changeLogs.saveAndFlush(entry);
    }

    private static double chartTotal(ProjectMetricsView view, String seriesKey) {
        ChartSeries series = view.series().stream()
                .filter(candidate -> seriesKey.equals(candidate.key()))
                .findFirst()
                .orElseThrow();
        return series.points().stream()
                .map(SeriesPoint::value)
                .filter(value -> value != null)
                .mapToDouble(Double::doubleValue)
                .sum();
    }
}
