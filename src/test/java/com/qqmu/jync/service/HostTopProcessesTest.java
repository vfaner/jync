package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The ps parser behind the CPU card's top-5 list. Only the executable name may leave the
 * server: command lines carry the product's own -D passwords.
 */
class HostTopProcessesTest {

    @Test
    void parsesPercentPidAndName() {
        String out = """
                  12.5   4242 java
                   3.1   8000 postgres
                   0.0      1 systemd
                """;
        List<HostTopProcesses.ProcessSample> rows = HostTopProcesses.parse(out, 5);

        assertThat(rows).extracting(HostTopProcesses.ProcessSample::name)
                .containsExactly("java", "postgres", "systemd");
        assertThat(rows.get(0).pid()).isEqualTo(4242);
        assertThat(rows.get(0).cpu()).isEqualTo(12.5);
    }

    @Test
    void keepsOnlyTheRequestedLimit() {
        String out = "9.0 1 a\n8.0 2 b\n7.0 3 c\n6.0 4 d\n";
        assertThat(HostTopProcesses.parse(out, 2))
                .extracting(HostTopProcesses.ProcessSample::name)
                .containsExactly("a", "b");
    }

    @Test
    void reducesAFullPathToItsBasename() {
        String out = "5.0 10 /Library/Java/JavaVirtualMachines/jdk/bin/java\n";
        assertThat(HostTopProcesses.parse(out, 5))
                .extracting(HostTopProcesses.ProcessSample::name)
                .containsExactly("java");
    }

    @Test
    void windowsOutputWithWholePercentValuesIsParsed() {
        // PowerShell's perf counter prints an integer percent; same three columns.
        String out = "12 4242 chrome\n7 8899 svchost\n0 4 System\n";
        assertThat(HostTopProcesses.parse(out, 5))
                .extracting(HostTopProcesses.ProcessSample::name)
                .containsExactly("chrome", "svchost", "System");
        assertThat(HostTopProcesses.parse(out, 5).get(0).cpu()).isEqualTo(12.0);
    }

    @Test
    void aCommaDecimalAndGarbageLinesAreHandled() {
        String out = "4,2 11 worker\nnot a row\n\n";
        List<HostTopProcesses.ProcessSample> rows = HostTopProcesses.parse(out, 5);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).cpu()).isEqualTo(4.2);
    }
}
