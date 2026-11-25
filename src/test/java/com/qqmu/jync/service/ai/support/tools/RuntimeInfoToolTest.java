package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.version.VersionInfo;
import com.qqmu.jync.service.version.VersionService;

import tools.jackson.databind.ObjectMapper;

/**
 * The version/deployment answer must come from the running installation, not from the
 * knowledge base: an intranet install can have the update check switched off, and a container
 * upgrade differs from a jar upgrade.
 */
class RuntimeInfoToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));

    @Test
    void reportsTheRunningVersionAndContainerDeployment() throws Exception {
        VersionService versions = mock(VersionService.class);
        when(versions.snapshot()).thenReturn(VersionInfo.builder()
                .currentVersion("2.4.6")
                .latestVersion("2.4.6")
                .latestTag("v2.4.6")
                .releaseUrl("https://github.com/vfaner/jync/releases/tag/v2.4.6")
                .latestPublishedDate("2026-11-25")
                .state(VersionInfo.State.UP_TO_DATE)
                .build());
        RuntimeInfoTool tool = new RuntimeInfoTool(versions);

        System.setProperty("jync.container", "true");
        try {
            var out = mapper.readTree(mapper.writeValueAsString(
                    tool.execute(mapper.createObjectNode(), ctx)));

            assertThat(out.path("currentVersion").asString()).isEqualTo("2.4.6");
            assertThat(out.path("latestTag").asString()).isEqualTo("v2.4.6");
            assertThat(out.path("updateState").asString()).isEqualTo("UP_TO_DATE");
            assertThat(out.path("deployment").asString()).isEqualTo("container");
            assertThat(out.path("upgradeHint").asString()).contains("volume");
            assertThat(tool.allowedKeys()).isEmpty();
        } finally {
            System.clearProperty("jync.container");
        }
    }
}
