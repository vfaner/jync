package com.qqmu.jync.service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Load, CPU, memory and disk figures for the 资源使用 gauges on the dashboard.
 *
 * <p>Read from the JVM's own counters, so the page needs no extra dependency, no polling
 * thread and no sampling history. Every gauge degrades to 未知 on its own: Windows reports
 * no load average, a JVM without the {@code com.sun.management} counters has no CPU or
 * memory numbers, and the disk figure follows the volume the metadata store sits on — a
 * full H2 volume is the failure this page exists to make visible.
 */
@Component
public class SystemMetrics {

    private static final String UNKNOWN_TEXT = "—";
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long MIB = 1024L * 1024L;

    private final String datasourceUrl;
    private final Path workingDir;

    // Explicit because the package-private constructor below is the test seam: with two
    // constructors Spring can no longer pick the only public one on its own.
    @Autowired
    public SystemMetrics(@Value("${spring.datasource.url:}") String datasourceUrl) {
        this(datasourceUrl, Paths.get("").toAbsolutePath());
    }

    SystemMetrics(String datasourceUrl, Path workingDir) {
        this.datasourceUrl = datasourceUrl;
        this.workingDir = workingDir;
    }

    /** One reading, taken while the page is rendered (and every poll of the card). */
    public Snapshot snapshot() {
        return snapshot(ManagementFactory.getOperatingSystemMXBean(), metadataFileStore(),
                availableMemory());
    }

    Snapshot snapshot(OperatingSystemMXBean os, FileStore store) {
        return snapshot(os, store, availableMemory());
    }

    /**
     * @param availableBytes what the platform says is still usable, or a negative value to
     *                       fall back on the JVM's free-page count
     */
    Snapshot snapshot(OperatingSystemMXBean os, FileStore store, long availableBytes) {
        double loadPercent = -1;
        double loadAverage = -1;
        double cpuPercent = -1;
        long memoryUsed = -1;
        long memoryTotal = -1;
        int cores = 1;

        try {
            double load = os.getSystemLoadAverage();
            cores = Math.max(1, os.getAvailableProcessors());
            loadAverage = load;
            loadPercent = load < 0 ? -1 : load / cores * 100.0;
        } catch (RuntimeException e) {
            loadPercent = -1;
        }

        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            try {
                cpuPercent = percentOf(extended.getCpuLoad());
                long total = extended.getTotalMemorySize();
                // 已用 = 总量 − 可回收量。JVM 的 free 只是"完全空闲页"，macOS 和 Linux 都会
                // 把缓存页算进去，空闲机器上也能读成 95% 已用；平台能给出可回收量时优先用它。
                long available = availableBytes >= 0 ? availableBytes : extended.getFreeMemorySize();
                if (total > 0) {
                    memoryTotal = total;
                    if (available >= 0 && available <= total) {
                        memoryUsed = total - available;
                    }
                }
            } catch (RuntimeException e) {
                cpuPercent = -1;
                memoryUsed = -1;
                memoryTotal = -1;
            }
        }

        long diskUsed = -1;
        long diskTotal = -1;
        if (store != null) {
            try {
                long total = store.getTotalSpace();
                long usable = store.getUsableSpace();
                if (total > 0) {
                    diskTotal = total;
                    diskUsed = Math.max(0, total - usable);
                }
            } catch (IOException | RuntimeException e) {
                diskUsed = -1;
                diskTotal = -1;
            }
        }

        return new Snapshot(loadPercent, loadAverage, cores,
                cpuPercent, memoryUsed, memoryTotal, diskUsed, diskTotal, volumePath());
    }

    /**
     * The directory whose volume the disk gauge measures, for the caption under the number.
     *
     * <p>Without it "311.6 GB / 926.3 GB" says nothing about which disk is filling up — the
     * figure describes the metadata volume, not {@code /} and not the synced data.
     *
     * <p>Spelled the way the JDBC URL spells it ({@code ./data}, not the absolute path of the
     * working directory): the long prefix carries no information the reader did not type
     * themselves, and once the caption is truncated it pushes the one useful part out of
     * view. A datasource with no local file — remote, in-memory, unset — has no volume to
     * name, so it gets no label rather than a misleading one.
     */
    public String volumePath() {
        String file = h2File(datasourceUrl);
        if (file == null) {
            return null;
        }
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return slash < 0 ? "./" : file.substring(0, slash);
    }

    /**
     * The directory the H2 store lives in, taken from its JDBC URL; anything else (a remote
     * datasource, an in-memory store, a blank value) falls back to the working directory.
     */
    static Path dataRoot(String datasourceUrl, Path workingDir) {
        String file = h2File(datasourceUrl);
        if (file == null) {
            return workingDir;
        }
        try {
            Path parent = Paths.get(file).getParent();
            return parent == null ? workingDir : parent.toAbsolutePath();
        } catch (InvalidPathException e) {
            return workingDir;
        }
    }

    /** The H2 file path in the URL without its parameters, or null for anything else. */
    private static String h2File(String datasourceUrl) {
        String prefix = "jdbc:h2:file:";
        if (datasourceUrl == null || !datasourceUrl.startsWith(prefix)) {
            return null;
        }
        String file = datasourceUrl.substring(prefix.length());
        int parameters = file.indexOf(';');
        if (parameters >= 0) {
            file = file.substring(0, parameters);
        }
        return file.isBlank() ? null : file;
    }

    /**
     * How much memory the machine can still hand out, or -1 when neither platform path works.
     *
     * <p>Linux answers from the kernel's own {@code MemAvailable} estimate. macOS has no
     * {@code /proc}, so {@code vm_stat} is asked once — free plus the inactive, speculative
     * and purgeable pages, which is what Activity Monitor calls available. Anything else
     * (Windows, a container without /proc) stays unknown and the JVM figure is used instead.
     */
    private static long availableMemory() {
        try {
            String meminfo = Files.readString(Paths.get("/proc/meminfo"));
            long available = parseMemAvailable(meminfo);
            if (available > 0) {
                return available;
            }
        } catch (IOException | RuntimeException e) {
            // not Linux, or /proc is not mounted in this container
        }
        if (!System.getProperty("os.name", "").startsWith("Mac")) {
            return -1;
        }
        try {
            Process vmStat = new ProcessBuilder("vm_stat").redirectErrorStream(true).start();
            // Wait before reading: vm_stat exits on its own and prints one short page, so the
            // pipe buffer never fills; the timeout keeps a wedged process from stalling a page.
            if (!vmStat.waitFor(2, TimeUnit.SECONDS)) {
                vmStat.destroyForcibly();
                return -1;
            }
            String output = new String(vmStat.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseVmStatAvailable(output);
        } catch (IOException | InterruptedException | RuntimeException e) {
            return -1;
        }
    }

    /** {@code MemAvailable: 25165824 kB} — the kernel's estimate of what is reclaimable. */
    static long parseMemAvailable(String meminfo) {
        if (meminfo == null) {
            return -1;
        }
        for (String line : meminfo.split("\n")) {
            if (!line.startsWith("MemAvailable:")) {
                continue;
            }
            String[] parts = line.substring("MemAvailable:".length()).trim().split("\\s+");
            try {
                return Math.multiplyExact(Long.parseLong(parts[0]), 1024L);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException
                     | ArithmeticException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * {@code vm_stat} reports page counts and the page size on its first line; the pages the
     * system can reclaim are the free, inactive, speculative and purgeable ones.
     */
    static long parseVmStatAvailable(String vmStat) {
        if (vmStat == null) {
            return -1;
        }
        long pageSize = -1;
        long reclaimable = 0;
        for (String line : vmStat.split("\n")) {
            if (line.startsWith("Mach Virtual Memory Statistics")) {
                pageSize = firstNumberAfter(line, "page size of ");
                continue;
            }
            if (line.startsWith("Pages free:") || line.startsWith("Pages inactive:")
                    || line.startsWith("Pages speculative:")
                    || line.startsWith("Pages purgeable:")) {
                long pages = firstNumberAfter(line, ":");
                if (pages < 0) {
                    return -1;
                }
                reclaimable += pages;
            }
        }
        return pageSize > 0 ? reclaimable * pageSize : -1;
    }

    /** Reads the first run of digits after {@code marker}; -1 when there is none. */
    private static long firstNumberAfter(String line, String marker) {
        int at = line.indexOf(marker);
        if (at < 0) {
            return -1;
        }
        int start = at + marker.length();
        int end = start;
        while (end < line.length() && !Character.isDigit(line.charAt(end))) {
            end++;
        }
        int digits = end;
        while (digits < line.length() && Character.isDigit(line.charAt(digits))) {
            digits++;
        }
        if (digits == end) {
            return -1;
        }
        try {
            return Long.parseLong(line.substring(end, digits));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private FileStore metadataFileStore() {
        try {
            return Files.getFileStore(dataRoot(datasourceUrl, workingDir));
        } catch (IOException | RuntimeException e) {
            // The store directory may not exist yet, or sit on a volume this JVM cannot stat.
        }
        try {
            return Files.getFileStore(workingDir);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static double percentOf(double ratio) {
        if (!(ratio >= 0)) {
            return -1;
        }
        return Math.min(100.0, ratio * 100.0);
    }

    /**
     * One rendered reading. Percentages below zero mean the platform did not report the
     * metric, and the card shows 未知 instead of a made-up number.
     *
     * <p>{@code loadAverage} is the raw one-minute figure the ring's percentage is derived
     * from — uptime's number, which is what an operator compares against the core count.
     * {@code volumePath} names the volume the disk figures came from, spelled as configured,
     * so the page never leaves the reader guessing between {@code /}, a data directory and a
     * separate disk.
     */
    public record Snapshot(double loadPercent, double loadAverage, int cores, double cpuPercent,
                           long memoryUsedBytes, long memoryTotalBytes,
                           long diskUsedBytes, long diskTotalBytes, String volumePath) {

        public static final Snapshot UNKNOWN = new Snapshot(-1, -1, 0, -1, -1, -1, -1, -1, null);

        public String loadText() {
            return percentText(loadPercent);
        }

        /** The one-minute average, or 未知 where the platform reports no load at all. */
        public String loadAverageText() {
            return loadAverage < 0 ? UNKNOWN_TEXT : String.format(Locale.ROOT, "%.2f", loadAverage);
        }

        public String cpuText() {
            return percentText(cpuPercent);
        }

        public String memoryText() {
            return bytesText(memoryUsedBytes, memoryTotalBytes);
        }

        public String diskText() {
            String usage = bytesText(diskUsedBytes, diskTotalBytes);
            return volumePath == null || volumePath.isBlank() ? usage : usage + " · " + volumePath;
        }

        /** Used share of memory, for the ring; negative when the platform did not report it. */
        public double memoryPercent() {
            return ratio(memoryUsedBytes, memoryTotalBytes);
        }

        /** Used share of the metadata volume, for the ring; negative when unknown. */
        public double diskPercent() {
            return ratio(diskUsedBytes, diskTotalBytes);
        }

        public String memoryPercentText() {
            return percentText(memoryPercent());
        }

        public String diskPercentText() {
            return percentText(diskPercent());
        }

        public String loadTone() {
            return tone(loadPercent);
        }

        public String cpuTone() {
            return tone(cpuPercent);
        }

        public String memoryTone() {
            return tone(ratio(memoryUsedBytes, memoryTotalBytes));
        }

        public String diskTone() {
            return tone(ratio(diskUsedBytes, diskTotalBytes));
        }

        private static String percentText(double percent) {
            return percent < 0 ? UNKNOWN_TEXT : Math.round(percent) + "%";
        }

        private static String bytesText(long used, long total) {
            if (used < 0 || total <= 0) {
                return UNKNOWN_TEXT;
            }
            return size(used) + " / " + size(total);
        }

        private static String size(long bytes) {
            double gigabytes = bytes / (double) GIB;
            return gigabytes >= 1
                    ? String.format(Locale.ROOT, "%.1f GB", gigabytes)
                    : String.format(Locale.ROOT, "%.0f MB", bytes / (double) MIB);
        }

        private static double ratio(long used, long total) {
            return used < 0 || total <= 0 ? -1 : used * 100.0 / total;
        }

        private static String tone(double percent) {
            if (percent < 0) {
                return "none";
            }
            return percent >= 90 ? "danger" : percent >= 75 ? "warn" : "ok";
        }
    }
}
