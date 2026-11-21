package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The Linux/macOS/Windows probes behind the CPU card's 基础信息 rows. */
class HostInfoTest {

    private static final String CPUINFO = """
            processor	: 0
            model name	: Intel(R) Xeon(R) Platinum 8255C CPU @ 2.50GHz
            physical id	: 0
            core id		: 0

            processor	: 1
            model name	: Intel(R) Xeon(R) Platinum 8255C CPU @ 2.50GHz
            physical id	: 0
            core id		: 1

            processor	: 2
            model name	: Intel(R) Xeon(R) Platinum 8255C CPU @ 2.50GHz
            physical id	: 1
            core id		: 0

            processor	: 3
            model name	: Intel(R) Xeon(R) Platinum 8255C CPU @ 2.50GHz
            physical id	: 1
            core id		: 1
            """;

    @Test
    void readsTheModelNameFromCpuinfo() {
        assertThat(HostInfo.parseModelName(CPUINFO))
                .isEqualTo("Intel(R) Xeon(R) Platinum 8255C CPU @ 2.50GHz");
    }

    @Test
    void countsDistinctPhysicalCorePairsAcrossSockets() {
        // 2 sockets × 2 cores = 4 physical cores, not the 4 logical entries by accident
        assertThat(HostInfo.parsePhysicalCores(CPUINFO)).isEqualTo(4);
    }

    @Test
    void anArmKernelWithoutIdsReportsZeroSoTheCallerKeepsTheLogicalCount() {
        String arm = "processor\t: 0\nmodel name\t: ARMv8 Processor rev 1\n\n"
                + "processor\t: 1\nmodel name\t: ARMv8 Processor rev 1\n";
        assertThat(HostInfo.parsePhysicalCores(arm)).isZero();
    }

    @Test
    void readsTheProcessorNameFromTheWindowsRegistryQuery() {
        String reg = """
                HKEY_LOCAL_MACHINE\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0
                    ProcessorNameString    REG_SZ    Intel(R) Core(TM) i7-12700K
                """;
        assertThat(HostInfo.parseRegValue(reg))
                .isEqualTo("Intel(R) Core(TM) i7-12700K");
    }

    @Test
    void missingCpuinfoIsNotAFailure() {
        assertThat(HostInfo.parseModelName(null)).isNull();
        assertThat(HostInfo.parsePhysicalCores(null)).isZero();
    }
}
