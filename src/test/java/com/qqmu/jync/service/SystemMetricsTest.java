package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * The 资源使用 card on 系统信息: four gauges read from the JVM's own counters.
 *
 * <p>Everything must degrade to 未知 rather than throw: this page is what an operator opens
 * when something is wrong, so it cannot be the page that falls over. Load is reported per
 * core, the disk figure must follow the volume the metadata store actually sits on, and a
 * JVM without the {@code com.sun.management} counters (or Windows, which has no load
 * average) reports 未知 for that gauge alone.
 */
class SystemMetricsTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void loadIsNormalizedByCoreCount() {
        OperatingSystemMXBean os = mock(OperatingSystemMXBean.class);
        when(os.getSystemLoadAverage()).thenReturn(4.0);
        when(os.getAvailableProcessors()).thenReturn(8);

        SystemMetrics.Snapshot snapshot = new SystemMetrics("jdbc:h2:file:./data/jync", Paths.get("."))
                .snapshot(os, null);

        assertThat(snapshot.loadPercent()).isEqualTo(50.0);
        assertThat(snapshot.loadText()).isEqualTo("50%");
    }

    @Test
    void loadIsUnknownWhereThePlatformDoesNotReportIt() {
        OperatingSystemMXBean os = mock(OperatingSystemMXBean.class);
        when(os.getSystemLoadAverage()).thenReturn(-1.0);
        when(os.getAvailableProcessors()).thenReturn(4);

        SystemMetrics.Snapshot snapshot = new SystemMetrics("jdbc:h2:file:./data/jync", Paths.get("."))
                .snapshot(os, null);

        assertThat(snapshot.loadPercent()).isNegative();
        assertThat(snapshot.loadText()).isEqualTo("—");
        assertThat(snapshot.loadTone()).isEqualTo("none");
    }

    @Test
    void cpuAndMemoryComeFromTheExtendedBean() {
        com.sun.management.OperatingSystemMXBean os =
                mock(com.sun.management.OperatingSystemMXBean.class);
        when(os.getCpuLoad()).thenReturn(0.63);
        when(os.getTotalMemorySize()).thenReturn(16 * GIB);
        when(os.getFreeMemorySize()).thenReturn(2 * GIB);

        SystemMetrics.Snapshot snapshot = new SystemMetrics("jdbc:h2:file:./data/jync", Paths.get("."))
                .snapshot(os, null, -1);

        assertThat(snapshot.cpuPercent()).isEqualTo(63.0);
        assertThat(snapshot.memoryTotalBytes()).isEqualTo(16 * GIB);
        assertThat(snapshot.memoryUsedBytes()).isEqualTo(14 * GIB);
        assertThat(snapshot.memoryText()).isEqualTo("14.0 GB / 16.0 GB");
    }

    /**
     * The JVM's free-page count is not what an operator means by 已用: macOS and Linux keep
     * almost every page in cache, so the raw figure reads ~95% on an idle machine. When the
     * platform can report what is actually reclaimable, that is the number the gauge uses.
     */
    @Test
    void usedMemoryUsesTheOperableAvailableFigure() {
        com.sun.management.OperatingSystemMXBean os =
                mock(com.sun.management.OperatingSystemMXBean.class);
        when(os.getTotalMemorySize()).thenReturn(48 * GIB);
        when(os.getFreeMemorySize()).thenReturn(2 * GIB);

        SystemMetrics.Snapshot snapshot = new SystemMetrics("jdbc:h2:file:./data/jync", Paths.get("."))
                .snapshot(os, null, 24 * GIB);

        assertThat(snapshot.memoryUsedBytes()).isEqualTo(24 * GIB);
        assertThat(snapshot.memoryText()).isEqualTo("24.0 GB / 48.0 GB");
        assertThat(snapshot.memoryPercentText()).isEqualTo("50%");
    }

    @Test
    void theJvmFreeFigureIsTheFallbackWhenAvailabilityIsUnknown() {
        com.sun.management.OperatingSystemMXBean os =
                mock(com.sun.management.OperatingSystemMXBean.class);
        when(os.getTotalMemorySize()).thenReturn(48 * GIB);
        when(os.getFreeMemorySize()).thenReturn(2 * GIB);

        SystemMetrics.Snapshot snapshot = new SystemMetrics("jdbc:h2:file:./data/jync", Paths.get("."))
                .snapshot(os, null, -1);

        assertThat(snapshot.memoryUsedBytes()).isEqualTo(46 * GIB);
    }

    @Test
    void memAvailableIsReadFromProc() {
        String meminfo = "MemTotal:       50331648 kB\n"
                + "MemFree:          102400 kB\n"
                + "MemAvailable:   25165824 kB\n"
                + "Buffers:          204800 kB\n";

        assertThat(SystemMetrics.parseMemAvailable(meminfo)).isEqualTo(25165824L * 1024);
    }

    @Test
    void memAvailableIsUnknownWhenTheKernelDoesNotReportIt() {
        assertThat(SystemMetrics.parseMemAvailable("MemTotal: 1000 kB\nMemFree: 10 kB\n"))
                .isEqualTo(-1);
        assertThat(SystemMetrics.parseMemAvailable("")).isEqualTo(-1);
        assertThat(SystemMetrics.parseMemAvailable(null)).isEqualTo(-1);
    }

    @Test
    void vmStatAvailabilityAddsThePagesTheSystemCanReclaim() {
        String vmStat = "Mach Virtual Memory Statistics: (page size of 16384 bytes)\n"
                + "Pages free:                                  144203.\n"
                + "Pages active:                               1266470.\n"
                + "Pages inactive:                             1311246.\n"
                + "Pages speculative:                            19884.\n"
                + "Pages purgeable:                              48057.\n"
                + "Pages wired down:                            230224.\n";

        long expectedPages = 144203L + 1311246L + 19884L + 48057L;
        assertThat(SystemMetrics.parseVmStatAvailable(vmStat)).isEqualTo(expectedPages * 16384);
    }

    @Test
    void vmStatAvailabilityIsUnknownWhenTheOutputDoesNotParse() {
        assertThat(SystemMetrics.parseVmStatAvailable("no virtual memory here")).isEqualTo(-1);
        assertThat(SystemMetrics.parseVmStatAvailable(null)).isEqualTo(-1);
    }

    @Test
    void aBeanWithoutTheExtendedCountersLeavesThoseGaugesUnknown() {
        OperatingSystemMXBean os = mock(OperatingSystemMXBean.class);

        SystemMetrics.Snapshot snapshot = new SystemMetrics("jdbc:h2:file:./data/jync", Paths.get("."))
                .snapshot(os, null);

        assertThat(snapshot.cpuPercent()).isNegative();
        assertThat(snapshot.cpuText()).isEqualTo("—");
        assertThat(snapshot.memoryText()).isEqualTo("—");
    }

    @Test
    void diskComesFromTheStoreTheMetadataSitsOn() throws Exception {
        FileStore store = Files.getFileStore(Paths.get(".").toAbsolutePath());
        OperatingSystemMXBean os = mock(OperatingSystemMXBean.class);

        SystemMetrics.Snapshot snapshot = new SystemMetrics("jdbc:h2:file:./data/jync", Paths.get("."))
                .snapshot(os, store);

        assertThat(snapshot.diskTotalBytes()).isPositive();
        assertThat(snapshot.diskUsedBytes()).isBetween(0L, snapshot.diskTotalBytes());
        assertThat(snapshot.diskText()).contains(" / ");
    }

    @Test
    void theDataRootIsTakenFromAnH2FileUrl() {
        Path root = SystemMetrics.dataRoot(
                "jdbc:h2:file:./data/jync;MODE=MySQL;AUTO_SERVER=TRUE", Paths.get("/srv/jync"));

        assertThat(root).isEqualTo(Paths.get("./data").toAbsolutePath());
    }

    @Test
    void aRemoteDatasourceFallsBackToTheWorkingDirectory() {
        Path cwd = Paths.get("/srv/jync");

        assertThat(SystemMetrics.dataRoot("jdbc:mysql://db:3306/jync", cwd)).isEqualTo(cwd);
        assertThat(SystemMetrics.dataRoot("", cwd)).isEqualTo(cwd);
        assertThat(SystemMetrics.dataRoot(null, cwd)).isEqualTo(cwd);
    }

    /**
     * 环上是「占几核的百分之多少」，下面的说明给 1 分钟平均负载本身：运维看的是
     * uptime 那个数，光有百分比既不知道一台几核的机器现在有多忙，也不知道绝对负载。
     */
    @Test
    void theLoadAverageIsReportedNextToItsPercentage() {
        OperatingSystemMXBean os = mock(OperatingSystemMXBean.class);
        when(os.getSystemLoadAverage()).thenReturn(4.0);
        when(os.getAvailableProcessors()).thenReturn(8);

        SystemMetrics.Snapshot snapshot = new SystemMetrics("jdbc:h2:file:./data/jync", Paths.get("."))
                .snapshot(os, null);

        assertThat(snapshot.loadAverage()).isEqualTo(4.0);
        assertThat(snapshot.loadAverageText()).isEqualTo("4.00");
        assertThat(SystemMetrics.Snapshot.UNKNOWN.loadAverageText()).isEqualTo("—");
    }

    /**
     * 磁盘那格必须说清楚量的是哪块卷：H2 元数据库在 ./data 下时，数字来自该目录所在
     * 卷，而不是「/」或某个数据目录。
     *
     * <p>说明里写配置里的那个写法（./data），不写解析后的绝对路径：工作目录那一长串
     * 前缀对读的人没有信息量，截断后反而把有意义的 ./data 挤没了。
     */
    @Test
    void theSnapshotNamesTheVolumeTheDiskFiguresComeFrom() throws Exception {
        FileStore store = Files.getFileStore(Paths.get(".").toAbsolutePath());
        OperatingSystemMXBean os = mock(OperatingSystemMXBean.class);

        SystemMetrics.Snapshot snapshot = new SystemMetrics("jdbc:h2:file:./data/jync", Paths.get("."))
                .snapshot(os, store);

        assertThat(snapshot.volumePath()).isEqualTo("./data");
        assertThat(snapshot.diskText()).endsWith(" · ./data");
    }

    /** 绝对路径按配置原样显示；没有本地卷可说的数据源（远程 HTTP/MySQL）不编一个出来。 */
    @Test
    void theVolumeLabelKeepsTheConfiguredSpelling() {
        Path cwd = Paths.get("/opt/jync");

        assertThat(new SystemMetrics("jdbc:h2:file:/srv/jync/data/jync;AUTO_SERVER=TRUE", cwd)
                .volumePath()).isEqualTo("/srv/jync/data");
        assertThat(new SystemMetrics("jdbc:mysql://db:3306/jync", cwd).volumePath()).isNull();
        assertThat(new SystemMetrics("jdbc:h2:mem:test", cwd).volumePath()).isNull();
        assertThat(new SystemMetrics("", cwd).volumePath()).isNull();
    }

    @Test
    void textAndToneFormatForTheCard() {
        SystemMetrics.Snapshot snapshot = new SystemMetrics.Snapshot(
                50.0, 4.0, 8, 63.0, 2 * GIB, 4 * GIB, 95 * GIB, 100 * GIB, null);

        assertThat(snapshot.loadText()).isEqualTo("50%");
        assertThat(snapshot.cpuText()).isEqualTo("63%");
        assertThat(snapshot.loadTone()).isEqualTo("ok");
        assertThat(snapshot.cpuTone()).isEqualTo("ok");
        assertThat(snapshot.memoryTone()).isEqualTo("ok");
        assertThat(snapshot.diskTone()).isEqualTo("danger");
        // 内存/磁盘的环上放占比，已用量作为环下的说明：两位数塞不进 92px 的环。
        assertThat(snapshot.memoryPercentText()).isEqualTo("50%");
        assertThat(snapshot.diskPercentText()).isEqualTo("95%");
        assertThat(SystemMetrics.Snapshot.UNKNOWN.memoryPercentText()).isEqualTo("—");
        assertThat(SystemMetrics.Snapshot.UNKNOWN.loadText()).isEqualTo("—");
        assertThat(SystemMetrics.Snapshot.UNKNOWN.diskTone()).isEqualTo("none");
    }

    @Test
    void aThrowingBeanDoesNotBreakThePage() {
        OperatingSystemMXBean os = mock(OperatingSystemMXBean.class);
        when(os.getSystemLoadAverage()).thenThrow(new UnsupportedOperationException("no load"));
        SystemMetrics metrics = new SystemMetrics("jdbc:h2:file:./data/jync", Paths.get("."));

        assertThatCode(() -> metrics.snapshot(os, null)).doesNotThrowAnyException();
        assertThat(metrics.snapshot(os, null).loadText()).isEqualTo("—");
    }
}
