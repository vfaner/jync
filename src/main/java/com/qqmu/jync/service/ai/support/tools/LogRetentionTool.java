package com.qqmu.jync.service.ai.support.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.qqmu.jync.config.MetricsProperties;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;

import tools.jackson.databind.JsonNode;

/**
 * The retention windows in effect for the three logs the pages show: change log, audit log,
 * application log, plus the metrics history. Read-only and cheap — the values are the running
 * configuration, not a query.
 *
 * <p>Answers "为什么日志/指标少了""保留多久" without the model guessing from the knowledge
 * base: an installation may have changed these in {@code application.yml}, and only the
 * effective value is correct. Purging by date stays a manual page action — the assistant
 * cannot delete logs.
 */
@Component
public class LogRetentionTool implements SupportTool {

    private final SyncProperties sync;
    private final MetricsProperties metrics;
    private final int logMaxHistoryDays;

    public LogRetentionTool(SyncProperties sync, MetricsProperties metrics,
                            @Value("${logging.logback.rollingpolicy.max-history:30}")
                            int logMaxHistoryDays) {
        this.sync = sync;
        this.metrics = metrics;
        this.logMaxHistoryDays = logMaxHistoryDays;
    }

    public String name() {
        return "log_retention";
    }

    public String description() {
        return "Read the retention windows currently in effect: change-log days "
                + "(sync.change-log-retention-days), audit-log days (sync.audit-retention-days), "
                + "application-log days (logback max-history) and metrics days "
                + "(metrics.retention-days). Use when asked how long logs or metrics are kept, "
                + "why old entries disappeared, or whether a date purge is allowed. No arguments. "
                + "Cheap; deleting logs itself is a manual action on the page.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    public Set<String> allowedKeys() {
        return Set.of();
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changeLogDays", sync.getChangeLogRetentionDays());
        out.put("auditLogDays", sync.getAuditRetentionDays());
        out.put("applicationLogDays", logMaxHistoryDays);
        out.put("metricsDays", metrics.getRetentionDays());
        out.put("datePurgeAllowedOn", "change log, audit log and system log pages "
                + "(up to the last completed day; today is never purgeable)");
        out.put("note", "A value of zero or less disables automatic pruning for that store. "
                + "Retention is applied by scheduled pruners; the assistant cannot delete logs.");
        return out;
    }
}
