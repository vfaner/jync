package com.qqmu.jync.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Static facts about the machine the gauges describe: the CPU model, physical and logical
 * core counts, and the OS line. Read once (the values cannot change while the JVM runs) and
 * cached, so the 5-second gauge refresh never spawns a process.
 *
 * <p>Java exposes the logical count and nothing else, so the model and physical count come
 * from the platform's own files/commands — {@code /proc/cpuinfo} on Linux and {@code sysctl}
 * on macOS. Anything unreadable stays null/unknown and the hover card simply omits that row
 * instead of guessing.
 */
@Component
public class HostInfo {

    /** Display facts; {@code model} is null when the platform would not tell us. */
    public record Cpu(String model, int physicalCores, int logicalCores, String osText) {
    }

    private volatile Cpu cached;

    public Cpu cpu() {
        Cpu value = cached;
        if (value == null) {
            synchronized (this) {
                value = cached;
                if (value == null) {
                    value = read();
                    cached = value;
                }
            }
        }
        return value;
    }

    private static Cpu read() {
        String osName = System.getProperty("os.name", "");
        int logical = Math.max(1, Runtime.getRuntime().availableProcessors());
        String osText = (osName + " " + System.getProperty("os.version", "")
                + " (" + System.getProperty("os.arch", "") + ")").trim();

        String model = null;
        int physical = logical;
        if (osName.startsWith("Mac")) {
            model = trimToNull(run("sysctl", "-n", "machdep.cpu.brand_string"));
            Integer cores = parseInt(run("sysctl", "-n", "hw.physicalcpu"));
            if (cores != null && cores > 0) {
                physical = Math.min(cores, logical);
            }
        } else if (osName.startsWith("Linux")) {
            String cpuinfo = readFile("/proc/cpuinfo");
            model = trimToNull(parseModelName(cpuinfo));
            int cores = parsePhysicalCores(cpuinfo);
            if (cores > 0) {
                physical = Math.min(cores, logical);
            }
        } else if (osName.startsWith("Windows")) {
            // 型号在注册表里，读一次很快；物理核心数只能问 CIM/WMI，冷启动约一两秒，
            // 所以整份结果在 HostInfo 里只取一次并缓存，不跟 5 秒刷新。
            model = trimToNull(parseRegValue(run(3, "reg", "query",
                    "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                    "/v", "ProcessorNameString")));
            Integer cores = parseInt(run(6, "powershell", "-NoProfile", "-NonInteractive",
                    "-Command", "(Get-CimInstance Win32_Processor"
                            + " | Measure-Object -Property NumberOfCores -Sum).Sum"));
            if (cores != null && cores > 0) {
                physical = Math.min(cores, logical);
            }
        }
        return new Cpu(model, physical, logical, osText);
    }

    /** {@code model name : Intel(R) Xeon(R) ...} from /proc/cpuinfo, or null. */
    static String parseModelName(String cpuinfo) {
        if (cpuinfo == null) {
            return null;
        }
        for (String line : cpuinfo.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            if ("model name".equalsIgnoreCase(key) || "Model".equalsIgnoreCase(key)) {
                String value = line.substring(colon + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * Distinct (physical id, core id) pairs; 0 when the kernel does not publish them (many
     * ARM kernels, containers). The caller then keeps the logical count.
     */
    static int parsePhysicalCores(String cpuinfo) {
        if (cpuinfo == null) {
            return 0;
        }
        Set<String> pairs = new HashSet<>();
        String physical = null;
        String core = null;
        for (String line : cpuinfo.split("\n")) {
            if (line.trim().isEmpty() || line.startsWith("processor")) {
                if (physical != null && core != null) {
                    pairs.add(physical + "/" + core);
                }
                physical = null;
                core = null;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("physical id".equalsIgnoreCase(key)) {
                physical = value;
            } else if ("core id".equalsIgnoreCase(key)) {
                core = value;
            }
        }
        if (physical != null && core != null) {
            pairs.add(physical + "/" + core);
        }
        return pairs.size();
    }

    /**
     * {@code reg query ... ProcessorNameString} prints one value line:
     * {@code "    ProcessorNameString    REG_SZ    Intel(R) Core(TM) i7-..."}.
     */
    static String parseRegValue(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\r?\n")) {
            int reg = line.indexOf("REG_SZ");
            if (reg >= 0) {
                return line.substring(reg + "REG_SZ".length()).trim();
            }
        }
        return null;
    }

    private static String run(String... command) {
        return run(2, command);
    }

    private static String run(long timeoutSeconds, String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String readFile(String path) {
        try {
            return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Integer parseInt(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
