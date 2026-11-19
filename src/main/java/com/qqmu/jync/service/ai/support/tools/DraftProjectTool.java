package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.service.CaptureStrategyParser;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportDraftStore;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.Ownership;

import tools.jackson.databind.JsonNode;

/**
 * The one write-shaped tool: it does not write. A well-formed request becomes an in-memory
 * draft token; only the user, on the prefilled form, can save it.
 */
@Component
public class DraftProjectTool implements SupportTool {

    private final DatabaseConfigService configs;
    private final RedisTargetRepository redisTargets;
    private final SupportDraftStore drafts;

    public DraftProjectTool(DatabaseConfigService configs, RedisTargetRepository redisTargets,
                            SupportDraftStore drafts) {
        this.configs = configs;
        this.redisTargets = redisTargets;
        this.drafts = drafts;
    }

    public String name() {
        return "draft_project";
    }

    public String description() {
        return "Draft a new sync project from a description. Saves nothing: returns a token the "
                + "user opens to get a prefilled form they must review and save. sourceDbId and "
                + "targetDbId come from list_connections.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "sourceDbId", Map.of("type", "integer"),
                        "targetDbId", Map.of("type", "integer"),
                        "targetKind", Map.of("type", "string", "enum", List.of("JDBC", "REDIS")),
                        "targetRedisId", Map.of("type", "integer"),
                        "suggestion", Map.of("type", "string")),
                "required", List.of("name", "sourceDbId"));
    }

    public Set<String> allowedKeys() {
        return Set.of("name", "description", "sourceDbId", "targetDbId", "targetKind",
                "targetRedisId", "suggestion");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        List<Map<String, Object>> errors = new ArrayList<>();
        String name = SupportArgs.optionalText(args, "name");
        if (name == null || name.length() > 128) {
            errors.add(error("name", "error.support.tool.args"));
        }
        String description = SupportArgs.optionalText(args, "description");
        if (description != null && description.length() > 512) {
            errors.add(error("description", "error.support.tool.args"));
        }
        String suggestion = SupportArgs.optionalText(args, "suggestion");

        Long sourceDbId = SupportArgs.optionalLong(args, "sourceDbId");
        DatabaseConfig source = sourceDbId == null
                ? null : configs.findById(sourceDbId).orElse(null);
        boolean sourceUsable = false;
        if (source == null) {
            errors.add(error("sourceDbId", "error.project.source.required"));
        } else {
            try {
                Ownership.requireReferenceable(source.getOwnerUsername(), source.getName(),
                        ctx.actor());
                if (source.getRole() != ConnectionRole.SOURCE || !source.supportsSyncSource()) {
                    errors.add(error("sourceDbId", "error.project.source.invalid"));
                } else {
                    sourceUsable = true;
                }
            } catch (IllegalArgumentException e) {
                errors.add(error("sourceDbId", e.getMessage()));
            }
        }

        String targetKind = SupportArgs.optionalText(args, "targetKind");
        SyncTargetKind kind = "REDIS".equalsIgnoreCase(targetKind)
                ? SyncTargetKind.REDIS : SyncTargetKind.JDBC;
        Long targetDbId = SupportArgs.optionalLong(args, "targetDbId");
        Long targetRedisId = SupportArgs.optionalLong(args, "targetRedisId");
        String targetLabel;
        if (kind == SyncTargetKind.REDIS) {
            RedisTarget target = targetRedisId == null
                    ? null : redisTargets.findById(targetRedisId).orElse(null);
            if (target == null) {
                errors.add(error("targetRedisId", "error.project.target.required"));
            } else {
                try {
                    Ownership.requireReferenceable(target.getOwnerUsername(), target.getName(),
                            ctx.actor());
                } catch (IllegalArgumentException e) {
                    errors.add(error("targetRedisId", e.getMessage()));
                }
            }
            targetLabel = target == null ? "" : target.getName();
            // A cache projection is CDC-only. The save path refuses a source with no change
            // log, so a draft that skipped the check would hand the user a token whose form
            // cannot be saved.
            if (sourceUsable && !cdcCapable(source)) {
                errors.add(error("sourceDbId", "error.project.redis.sourceNoCdc"));
            }
        } else {
            DatabaseConfig target = targetDbId == null
                    ? null : configs.findById(targetDbId).orElse(null);
            if (target == null) {
                errors.add(error("targetDbId", "error.project.target.required"));
            } else {
                try {
                    Ownership.requireReferenceable(target.getOwnerUsername(), target.getName(),
                            ctx.actor());
                    if (target.getRole() != ConnectionRole.TARGET
                            || !target.supportsSyncTarget()) {
                        errors.add(error("targetDbId", "error.project.target.invalid"));
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(error("targetDbId", e.getMessage()));
                }
            }
            targetLabel = target == null ? "" : target.getName();
        }

        if (!errors.isEmpty()) {
            return Map.of("ok", false, "errors", errors);
        }

        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setSourceDbId(sourceDbId);
        project.setTargetKind(kind);
        if (kind == SyncTargetKind.REDIS) {
            project.setTargetRedisId(targetRedisId);
        } else {
            project.setTargetDbId(targetDbId);
        }
        project.setOwnerUsername(ctx.actor().username());
        String token = drafts.put(ctx.actor().username(), project, suggestion);

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("support.draft.field.name", name));
        fields.add(field("support.draft.field.source", source.getName()));
        fields.add(field("support.draft.field.target", targetLabel));
        if (suggestion != null) {
            fields.add(field("support.draft.field.suggestion", suggestion));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("title", name);
        out.put("summary", suggestion == null ? "" : suggestion);
        out.put("fields", fields);
        return out;
    }

    /** Mirrors {@code RedisProjectionPolicy.requireCdcCapableSource} without its service deps. */
    private static boolean cdcCapable(DatabaseConfig source) {
        return CaptureStrategyParser.supportedFor(source.getType(), source.resolvedFamily())
                .stream()
                .anyMatch(CaptureStrategy::isLogCdc);
    }

    private Map<String, Object> error(String field, String messageKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", field);
        out.put("messageKey", messageKey);
        return out;
    }

    private Map<String, Object> field(String labelKey, String value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labelKey", labelKey);
        out.put("value", value);
        return out;
    }
}
