package com.qqmu.jync.service.ai.support.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.metrics.DbMetricsView;
import com.qqmu.jync.service.metrics.MetricsQueryService;
import com.qqmu.jync.service.metrics.MetricsRange;
import tools.jackson.databind.JsonNode;

@Component
public class DatabaseMetricsTool implements SupportTool {

    private final MetricsQueryService metrics;
    private final DatabaseConfigService configs;

    public DatabaseMetricsTool(MetricsQueryService metrics, DatabaseConfigService configs) {
        this.metrics = metrics;
        this.configs = configs;
    }

    public String name() {
        return "database_metrics";
    }

    public String description() {
        return "Read one connection's performance metrics over 1h or 24h.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "databaseId", Map.of("type", "integer"),
                        "window", Map.of("type", "string",
                                "enum", List.of("1h", "24h"))),
                "required", List.of("databaseId"));
    }

    public Set<String> allowedKeys() {
        return Set.of("databaseId", "window");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        long databaseId = SupportArgs.requireLong(args, "databaseId");
        String window = SupportArgs.optionalText(args, "window");
        MetricsRange range = switch (window == null ? "1h" : window) {
            case "1h" -> MetricsRange.LAST_HOUR;
            case "24h" -> MetricsRange.LAST_DAY;
            // 7d is deliberately refused: it is a report, not a chat answer, and it costs an
            // hour of samples to build. Any other value is a model mistake, answered the same
            // way as a malformed argument.
            default -> null;
        };
        if (range == null) {
            return Map.of("error", SupportArgs.BAD_ARGS);
        }
        if (configs.findById(databaseId).isEmpty()) {
            return Map.of("error", "error.db.not.found");
        }
        DbMetricsView view = metrics.database(databaseId, range);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", view.ok());
        out.put("message", view.message());
        out.put("lastSample", view.lastSample() == null ? null : view.lastSample().toString());
        out.put("uptimePercent", view.uptimePercent());
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("sessions", view.current().get("sessions"));
        current.put("qps", view.current().get("qps"));
        current.put("cacheHit", view.current().get("cacheHit"));
        current.put("locks", view.current().get("locks"));
        current.put("latency", view.current().get("latency"));
        out.put("current", current);
        return out;
    }
}
