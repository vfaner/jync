package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.qqmu.jync.config.MetricsProperties;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;

/**
 * The retention windows the assistant reports must be the ones actually in force: an
 * installation that changed them in application.yml should never hear a knowledge-base number.
 */
class LogRetentionToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));

    @Test
    void reportsTheEffectiveRetentionWindows() throws Exception {
        SyncProperties sync = new SyncProperties();
        sync.setChangeLogRetentionDays(180);
        sync.setAuditRetentionDays(90);
        MetricsProperties metrics = new MetricsProperties();
        metrics.setRetentionDays(7);

        var tool = new LogRetentionTool(sync, metrics, 30);
        var out = mapper.readTree(mapper.writeValueAsString(
                tool.execute(mapper.createObjectNode(), ctx)));

        assertThat(out.path("changeLogDays").asInt()).isEqualTo(180);
        assertThat(out.path("auditLogDays").asInt()).isEqualTo(90);
        assertThat(out.path("applicationLogDays").asInt()).isEqualTo(30);
        assertThat(out.path("metricsDays").asInt()).isEqualTo(7);
        assertThat(out.path("datePurgeAllowedOn").asString()).contains("system log");
        assertThat(tool.allowedKeys()).isEmpty();
    }
}
