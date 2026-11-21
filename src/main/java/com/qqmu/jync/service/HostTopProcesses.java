package com.qqmu.jync.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * The busiest processes on this host, for the CPU gauge's hover card.
 *
 * <p>Only the executable name is read — never the full command line, which routinely carries
 * passwords and connection strings in this product's own {@code java -D...} arguments. The
 * list is sampled on demand (when the card is opened) and cached for a few seconds, so the
 * dashboard's 5-second refresh never turns into a process-table poll.
 *
 * <p>Linux uses procps' {@code ps --sort}; macOS sorts with {@code -r}; anything else reports
 * {@code supported=false} and the page says so instead of showing an empty list as "idle".
 */
@Component
public class HostTopProcesses {

    /** One row: process name only, no arguments. */
    public record ProcessSample(int pid, String name, double cpu) {
    }

    /** {@code supported=false} when the platform has no sampler wired up. */
    public record TopProcesses(boolean supported, List<ProcessSample> processes) {
    }

    private static final long CACHE_MS = 10_000L;
    private static final int MAX_NAME = 60;

    /** {@code pcpu pid comm} with "=" formats (no header). */
    private static final Pattern ROW =
            Pattern.compile("^\\s*([0-9]+(?:[.,][0-9]+)?)\\s+(\\d+)\\s+(.+?)\\s*$");

    private volatile TopProcesses cached;
    private volatile long cachedAt;

    public TopProcesses topCpu(int limit) {
        long now = System.currentTimeMillis();
        TopProcesses value = cached;
        if (value != null && now - cachedAt < CACHE_MS) {
            return value;
        }
        value = sample(limit);
        cached = value;
        cachedAt = System.currentTimeMillis();
        return value;
    }

    private static TopProcesses sample(int limit) {
        String os = System.getProperty("os.name", "");
        List<String> command;
        long timeoutSeconds = 2;
        if (os.startsWith("Mac")) {
            command = List.of("ps", "-Ao", "pcpu=,pid=,ucomm=", "-r");
        } else if (os.startsWith("Linux")) {
            command = List.of("ps", "-eo", "pcpu=,pid=,comm=", "--sort=-pcpu");
        } else if (os.startsWith("Windows")) {
            // 没有 ps：用系统自带的 PowerShell 读性能计数器，输出与 ps 同形的三列。
            // 冷启动较慢，所以只在用户点开时取一次，并由 10 秒缓存兜住连点。
            command = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-CimInstance Win32_PerfFormattedData_PerfProc_Process"
                            + " | Where-Object { $_.Name -ne '_Total' -and $_.Name -ne 'Idle' }"
                            + " | Sort-Object PercentProcessorTime -Descending"
                            + " | Select-Object -First 5"
                            + " | ForEach-Object { '{0} {1} {2}' -f"
                            + " $_.PercentProcessorTime, $_.IDProcess, $_.Name }");
            timeoutSeconds = 8;
        } else {
            return new TopProcesses(false, List.of());
        }
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new TopProcesses(true, List.of());
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return new TopProcesses(true, parse(output, limit));
        } catch (IOException | RuntimeException e) {
            return new TopProcesses(true, List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TopProcesses(true, List.of());
        }
    }

    /**
     * Parses {@code ps} output. Names are reduced to their basename and stripped of control
     * characters: macOS may render a full path, and a name is the only field the page shows.
     */
    static List<ProcessSample> parse(String output, int limit) {
        List<ProcessSample> rows = new ArrayList<>();
        if (output == null) {
            return rows;
        }
        for (String line : output.split("\n")) {
            if (rows.size() >= limit) {
                break;
            }
            Matcher match = ROW.matcher(line);
            if (!match.matches()) {
                continue;
            }
            double cpu;
            int pid;
            try {
                cpu = Double.parseDouble(match.group(1).replace(',', '.'));
                pid = Integer.parseInt(match.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            String name = match.group(3).trim();
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            name = name.replaceAll("[\\p{Cntrl}]", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.length() > MAX_NAME) {
                name = name.substring(0, MAX_NAME);
            }
            rows.add(new ProcessSample(pid, name, cpu));
        }
        return rows;
    }
}
