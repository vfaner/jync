package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DeploymentModeTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty("jync.container");
    }

    @Test
    void anExplicitOverrideWins() {
        System.setProperty("jync.container", "true");
        assertThat(DeploymentMode.isContainer()).isTrue();

        System.setProperty("jync.container", "false");
        assertThat(DeploymentMode.isContainer()).isFalse();
    }
}
