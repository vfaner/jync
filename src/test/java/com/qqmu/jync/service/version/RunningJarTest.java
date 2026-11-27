package com.qqmu.jync.service.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RunningJarTest {

    @Test
    void resolvesClassicSpringBootJarUrl() {
        assertThat(RunningJar.fromJarSpec(
                "jar:file:/opt/jync/jync.jar!/BOOT-INF/classes!/"))
                .isEqualTo(Path.of("/opt/jync/jync.jar"));
    }

    @Test
    void resolvesNestedSpringBootJarUrlWithFileScheme() {
        assertThat(RunningJar.fromJarSpec(
                "jar:nested:file:/opt/jync/jync.jar/!BOOT-INF/classes!/"))
                .isEqualTo(Path.of("/opt/jync/jync.jar"));
    }

    @Test
    void resolvesNestedSpringBootJarUrlWithRawPath() {
        assertThat(RunningJar.fromJarSpec(
                "jar:nested:/opt/jync/jync.jar/!BOOT-INF/classes!/"))
                .isEqualTo(Path.of("/opt/jync/jync.jar"));
    }
}
