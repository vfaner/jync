package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * B2 能力探测:八项检查,任何一项不过 => passed()==false 且 failures() 指出缺什么。
 * 探测失败不抛异常、不阻断保存项目(任务书 B2)。
 *
 * <p>Mock 意图:setUp 用 conn/stmt 两个 mock 拼出一台"一切正常"的 MySQL 8 源库
 * (元数据 + SHOW VARIABLES + SHOW GRANTS 全部健康),各测试再翻转其中一项,
 * 断言只有对应检查项失败——探测逻辑只依赖字符串 SQL,不需要真实数据库。
 */
class BinlogCdcProbeTest {

    /** 可编程的假读取器:记录被打开的位置,可返回描述或抛异常。 */
    private static final class RecordingReader implements BinlogEventReader {
        private final List<String> opened = new ArrayList<>();
        private String description = "FormatDescriptionEvent (FORMAT_DESCRIPTION)";
        private Exception failure;

        @Override
        public String readFirstEvent(DatabaseConfig source, String binlogFile, long position,
                                     long serverId, Duration timeout) throws Exception {
            opened.add(binlogFile + ":" + position);
            if (failure != null) {
                throw failure;
            }
            return description;
        }
    }

    private final RecordingReader reader = new RecordingReader();
    private final BinlogCdcProbe probe = new BinlogCdcProbe(reader);
    private Connection conn;
    private Statement stmt;

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("MySQL");
        when(md.getDatabaseProductVersion()).thenReturn("8.0.36");
        when(conn.getMetaData()).thenReturn(md);
        // 默认:一切正常
        variable("log_bin", "ON");
        variable("binlog_format", "ROW");
        variable("binlog_row_image", "FULL");
        variable("server_id", "1");
        variable("binlog_expire_logs_seconds", "2592000");
        grants("GRANT ALL PRIVILEGES ON *.* TO 'sync'@'%'");
        binlogStatus("SHOW BINARY LOG STATUS", "mysql-bin.000003", 154L);
        // 默认:服务器只列出一个(当前)binlog 文件
        binlogFiles("mysql-bin.000003");
    }

    /** Mock SHOW BINARY LOGS:一行一个文件名。 */
    private void binlogFiles(String... files) throws SQLException {
        when(stmt.executeQuery("SHOW BINARY LOGS")).thenAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            Boolean[] tail = new Boolean[files.length];
            Arrays.fill(tail, Boolean.TRUE);
            tail[files.length - 1] = Boolean.FALSE;
            when(rs.next()).thenReturn(Boolean.TRUE, tail);
            when(rs.getString(1)).thenReturn(files[0],
                    Arrays.copyOfRange(files, 1, files.length));
            return rs;
        });
    }

    /** Mock SHOW BINARY LOG STATUS / SHOW MASTER STATUS:一行 File+Position。 */
    private void binlogStatus(String sql, String file, long pos) throws SQLException {
        when(stmt.executeQuery(sql)).thenAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("File")).thenReturn(file);
            when(rs.getLong("Position")).thenReturn(pos);
            return rs;
        });
    }

    /**
     * Mock 一行 SHOW VARIABLES 结果:next() 一次 true 后 false,getString(2) 给出变量值
     * (null 模拟列缺失)。每次 executeQuery 返回一份新 ResultSet——像真实服务器一样,
     * 同一测试内多次 probe() 每次都能读到相同的值。
     */
    private void variable(String name, String value) throws SQLException {
        when(stmt.executeQuery("SHOW VARIABLES LIKE '" + name + "'")).thenAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(2)).thenReturn(value);
            return rs;
        });
    }

    /**
     * Mock SHOW GRANTS 结果集:next() 共 rows.length 次 true 然后 false;
     * getString(1) 按调用次序返回 rows[0], rows[1], ...
     * (Mockito varargs 语义:thenReturn(首值, 后续序列),序列耗尽后重复末值)。
     * 每次 executeQuery 返回一份新结果集,支持重复 probe()。
     */
    private void grants(String... rows) throws SQLException {
        when(stmt.executeQuery("SHOW GRANTS")).thenAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            Boolean[] tail = new Boolean[rows.length];
            Arrays.fill(tail, Boolean.TRUE);
            tail[rows.length - 1] = Boolean.FALSE;
            when(rs.next()).thenReturn(Boolean.TRUE, tail);
            when(rs.getString(1)).thenReturn(rows[0], Arrays.copyOfRange(rows, 1, rows.length));
            return rs;
        });
    }

    private DatabaseConfig config() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.MYSQL);
        config.setHost("db.local");
        config.setPort(3306);
        config.setUsername("repl");
        config.setPassword("secret");
        return config;
    }

    private CdcProbeResult.Check check(CdcProbeResult result, String name) {
        return result.getChecks().stream()
                .filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void healthySourcePassesAllChecks() {
        CdcProbeResult result = probe.probe(conn, config(), 612001L);
        assertThat(result.passed()).isTrue();
        assertThat(result.getChecks()).hasSize(9);
    }

    // —— P1-5:位点真实可读检查。SHOW GRANTS 文本匹配不等于 REPLICATION CLIENT
    // 真的可用(角色授权/动态权限都会让文本失真);SHOW MASTER STATUS 在 8.4 已被
    // 移除,只发 SHOW BINARY LOG STATUS 又打不进 8.0-。基线读位点是流可开的前提,
    // probe 必须真实执行它。——

    @Test
    void binlogStatusReadsPositionOnModernServers() {
        CdcProbeResult.Check status = check(probe.probe(conn, config(), 612001L), "BINLOG_STATUS");
        assertThat(status.isPassed()).isTrue();
        assertThat(status.getDetail()).contains("mysql-bin.000003").contains("154");
    }

    @Test
    void masterStatusFallbackOnOlderServers() throws SQLException {
        // 8.0-:SHOW BINARY LOG STATUS 不存在 → 语法错误 → 落到 SHOW MASTER STATUS。
        when(stmt.executeQuery("SHOW BINARY LOG STATUS"))
                .thenThrow(new SQLException(
                        "You have an error in your SQL syntax near 'BINARY LOG STATUS'"));
        binlogStatus("SHOW MASTER STATUS", "mysql-bin.000007", 888L);

        CdcProbeResult.Check status = check(probe.probe(conn, config(), 612001L), "BINLOG_STATUS");
        assertThat(status.isPassed()).isTrue();
        assertThat(status.getDetail()).contains("mysql-bin.000007").contains("888");
    }

    @Test
    void bothStatusStatementsFailTheCheck() throws SQLException {
        // 8.4 上 REPLICATION CLIENT 缺失:两条都报 access denied → 检查失败并带错误详情。
        when(stmt.executeQuery("SHOW BINARY LOG STATUS"))
                .thenThrow(new SQLException("Access denied; you need REPLICATION CLIENT privilege"));
        when(stmt.executeQuery("SHOW MASTER STATUS"))
                .thenThrow(new SQLException(
                        "You have an error in your SQL syntax near 'MASTER STATUS'"));

        CdcProbeResult result = probe.probe(conn, config(), 612001L);
        CdcProbeResult.Check status = check(result, "BINLOG_STATUS");
        assertThat(status.isPassed()).isFalse();
        assertThat(result.passed()).isFalse();
    }

    @Test
    void binlogOffFailsLogBinOnly() throws SQLException {
        variable("log_bin", "OFF");
        CdcProbeResult result = probe.probe(conn, config(), 612001L);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).extracting(CdcProbeResult.Check::getName)
                .containsExactly("LOG_BIN");
    }

    @Test
    void statementFormatFails() throws SQLException {
        variable("binlog_format", "STATEMENT");
        CdcProbeResult result = probe.probe(conn, config(), 612001L);
        assertThat(check(result, "BINLOG_FORMAT").isPassed()).isFalse();
        assertThat(check(result, "BINLOG_FORMAT").getDetail()).contains("STATEMENT");
    }

    @Test
    void minimalRowImageFails() throws SQLException {
        variable("binlog_row_image", "MINIMAL");
        assertThat(probe.probe(conn, config(), 612001L).passed()).isFalse();
    }

    @Test
    void serverIdZeroOrConflictFails() throws SQLException {
        variable("server_id", "0");
        assertThat(check(probe.probe(conn, config(), 612001L), "SERVER_ID").isPassed()).isFalse();

        variable("server_id", "612001"); // 与本工具伪装 id 撞车
        CdcProbeResult conflict = probe.probe(conn, config(), 612001L);
        assertThat(check(conflict, "SERVER_ID").isPassed()).isFalse();
        assertThat(check(conflict, "SERVER_ID").getDetail()).contains("612001");

        // plannedServerId 未知(连接页探测)时只查非 0
        variable("server_id", "612001");
        assertThat(check(probe.probe(conn, config(), 0L), "SERVER_ID").isPassed()).isTrue();
    }

    @Test
    void serverIdConflictDetailPinsTheCollidesWording() throws SQLException {
        // CdcApiController.SERVER_ID_COLLISION_MARKER 靠 detail 里的 "collides" 一词
        // 推导 serverIdConflict;改掉措辞会让冲突标记静默变 false(前端不再给出
        // 调 server-id-offset 的指引)。这枚钉子保证探测侧与控制器侧同步。
        variable("server_id", "612001");
        CdcProbeResult.Check conflict = check(probe.probe(conn, config(), 612001L), "SERVER_ID");
        assertThat(conflict.isPassed()).isFalse();
        assertThat(conflict.getDetail()).contains("collides");

        // 非冲突的 SERVER_ID 失败(id 为 0)绝不能带这个词,否则控制器误判为冲突。
        variable("server_id", "0");
        CdcProbeResult.Check zero = check(probe.probe(conn, config(), 612001L), "SERVER_ID");
        assertThat(zero.isPassed()).isFalse();
        assertThat(zero.getDetail()).doesNotContain("collides");
    }

    @Test
    void missingReplicationGrantsFail() throws SQLException {
        grants("GRANT SELECT, INSERT ON `app`.* TO 'sync'@'%'");
        CdcProbeResult result = probe.probe(conn, config(), 612001L);
        assertThat(check(result, "REPLICATION_GRANTS").isPassed()).isFalse();
    }

    @Test
    void replicationSlaveOnlyIsNotEnough() throws SQLException {
        grants("GRANT REPLICATION SLAVE ON *.* TO 'sync'@'%'");
        assertThat(check(probe.probe(conn, config(), 612001L), "REPLICATION_GRANTS").isPassed()).isFalse();
    }

    @Test
    void replicationReplicaNameIsAcceptedOnNewServers() throws SQLException {
        // MySQL 8.0.22+ / 8.4 把 REPLICATION SLAVE 改名为 REPLICATION REPLICA,8.4 起
        // 不再输出旧名。只认 SLAVE 的文本匹配会把一个权限完全正确的账户判成「缺权限」,
        // 用户照着提示去加一个服务器已经不接受的权限名。
        grants("GRANT REPLICATION REPLICA ON *.* TO 'sync'@'%'",
                "GRANT REPLICATION CLIENT ON *.* TO 'sync'@'%'");

        CdcProbeResult.Check check = check(probe.probe(conn, config(), 612001L), "REPLICATION_GRANTS");

        assertThat(check.isPassed()).isTrue();
    }

    @Test
    void shortRetentionFailsWithAdvice() throws SQLException {
        variable("binlog_expire_logs_seconds", "3600"); // 1h,撑不过停机窗口
        CdcProbeResult.Check retention = check(probe.probe(conn, config(), 612001L), "BINLOG_RETENTION");
        assertThat(retention.isPassed()).isFalse();
        assertThat(retention.getDetail()).contains("3600");
    }

    @Test
    void legacyExpireLogsDaysUnderstood() throws SQLException {
        variable("binlog_expire_logs_seconds", null);
        variable("expire_logs_days", "7");
        CdcProbeResult.Check retention = check(probe.probe(conn, config(), 612001L), "BINLOG_RETENTION");
        assertThat(retention.isPassed()).isTrue();
        assertThat(retention.getDetail()).contains("7");
    }

    // —— v1.9.0: MySQL 系兼容库不再按品牌名拒绝，改由「真的解一条事件」决定 ——

    @Test
    void nativeMysqlSkipsTheStreamProbe() {
        // 原厂 MySQL 的线格式由生产中的消费端持续验证，探测不必再开一条流。
        CdcProbeResult.Check readable = check(probe.probe(conn, config(), 612001L),
                CdcProbeResult.BINLOG_EVENT_READABLE);
        assertThat(readable.isPassed()).isTrue();
        assertThat(readable.getDetail()).contains("native MySQL");
        assertThat(reader.opened).isEmpty();
    }

    @Test
    void mariadbIsAcceptedWhenItsEventsDecode() throws SQLException {
        // MariaDB 自己报告产品名，10.5+ 的 binlog 格式与 MySQL 分叉；能不能用由
        // 事件解码决定，不再由版本横幅一票否决。
        metadata("MariaDB", "11.4.2-MariaDB");
        CdcProbeResult result = probe.probe(conn, config(), 0L);
        assertThat(check(result, CdcProbeResult.VERSION).isPassed()).isTrue();
        assertThat(check(result, CdcProbeResult.BINLOG_EVENT_READABLE).isPassed()).isTrue();
        assertThat(reader.opened).containsExactly("mysql-bin.000003:4");
        assertThat(result.passed()).isTrue();
    }

    @Test
    void anUndecodableStreamFailsTheCapabilityCheckWithTheDecoderMessage() throws SQLException {
        metadata("MariaDB", "11.4.2-MariaDB");
        reader.failure = new CdcDecodeException("Unknown event type 160", null);
        CdcProbeResult result = probe.probe(conn, config(), 0L);
        CdcProbeResult.Check readable = check(result, CdcProbeResult.BINLOG_EVENT_READABLE);
        assertThat(readable.isPassed()).isFalse();
        assertThat(readable.getDetail()).contains("Unknown event type 160")
                .contains("cannot be decoded");
        assertThat(result.passed()).isFalse();
    }

    @Test
    void aConnectionFailureIsNotReportedAsAnIncompatibleFormat() throws SQLException {
        // 密码错 / 权限不足也是「打不开流」,但绝不能让用户以为这个产品格式不兼容。
        metadata("MariaDB", "11.4.2-MariaDB");
        reader.failure = new Exception("Access denied for user 'sync'@'%' (using password: YES)");

        CdcProbeResult.Check readable =
                check(probe.probe(conn, config(), 0L), CdcProbeResult.BINLOG_EVENT_READABLE);

        assertThat(readable.isPassed()).isFalse();
        assertThat(readable.getDetail()).contains("Access denied")
                .doesNotContain("cannot be decoded");
    }

    @Test
    void tidbBannerIsNotSubjectToTheNativeMysqlVersionFloor() throws SQLException {
        // TiDB 伪装成 MySQL 5.7.25；按原厂 MySQL 的版本下限判会误伤，交给事件探测。
        metadata("MySQL", "5.7.25-TiDB-v7.5.0");
        CdcProbeResult result = probe.probe(conn, config(), 0L);
        assertThat(check(result, CdcProbeResult.VERSION).isPassed()).isTrue();
        assertThat(reader.opened).hasSize(1);
    }

    @Test
    void ancientNativeMysqlIsStillRejected() throws SQLException {
        metadata("MySQL", "5.6.51-log");
        assertThat(check(probe.probe(conn, config(), 0L),
                CdcProbeResult.VERSION).isPassed()).isFalse();
    }

    @Test
    void mariadbBinlogMonitorSatisfiesTheReplicationClientGrant() throws SQLException {
        // MariaDB 10.5 把 REPLICATION CLIENT 改名为 BINLOG MONITOR(旧名仍可授予,
        // 但 SHOW GRANTS 只输出新名)。只认 REPLICATION CLIENT 会把一个权限完全正确的
        // MariaDB 账户判成「缺权限」。实测 MariaDB 11.8:GRANT SELECT, REPLICATION SLAVE,
        // BINLOG MONITOR ON *.* TO `sync`@`%`。
        metadata("MariaDB", "11.8.9-MariaDB");
        grants("GRANT SELECT, REPLICATION SLAVE, BINLOG MONITOR ON *.* TO `sync`@`%`");

        CdcProbeResult.Check check =
                check(probe.probe(conn, config(), 0L), CdcProbeResult.REPLICATION_GRANTS);

        assertThat(check.isPassed()).isTrue();
    }

    @Test
    void theStreamProbeReadsThePreviousBinlogFileWhenTheListHasOne() throws SQLException {
        // 当前 binlog 文件可能刚轮转、只有头事件;上一份文件才有行事件。从它开头读,
        // 探测才真的验到行格式,而不是只验到 FormatDescription。
        metadata("MariaDB", "11.8.9-MariaDB");
        binlogFiles("mysql-bin.000001", "mysql-bin.000002", "mysql-bin.000003");

        probe.probe(conn, config(), 0L);

        assertThat(reader.opened).containsExactly("mysql-bin.000002:4");
    }

    @Test
    void theStreamProbeFallsBackToTheCurrentFileWhenItIsTheOnlyOne() throws SQLException {
        metadata("MariaDB", "11.8.9-MariaDB");
        binlogFiles("mysql-bin.000003");

        probe.probe(conn, config(), 0L);

        assertThat(reader.opened).containsExactly("mysql-bin.000003:4");
    }

    @Test
    void anUnreadableFileListStillProbesTheCurrentFile() throws SQLException {
        // SHOW BINARY LOGS 需要 REPLICATION CLIENT;权限不足时退回当前文件,不因此判失败。
        metadata("MariaDB", "11.8.9-MariaDB");
        when(stmt.executeQuery("SHOW BINARY LOGS"))
                .thenThrow(new SQLException("Access denied; you need the REPLICATION CLIENT"
                        + " privilege for this operation"));

        CdcProbeResult result = probe.probe(conn, config(), 0L);

        assertThat(reader.opened).containsExactly("mysql-bin.000003:4");
        assertThat(check(result, CdcProbeResult.BINLOG_EVENT_READABLE).isPassed()).isTrue();
    }

    private void metadata(String product, String version) throws SQLException {
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn(product);
        when(md.getDatabaseProductVersion()).thenReturn(version);
        when(conn.getMetaData()).thenReturn(md);
    }
}
