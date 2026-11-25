package com.qqmu.jync.service.ai.support.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.SpringBootVersion;
import org.springframework.stereotype.Component;

import com.qqmu.jync.config.DeploymentMode;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.version.VersionInfo;
import com.qqmu.jync.service.version.VersionService;

import tools.jackson.databind.JsonNode;

/**
 * Read-only runtime and release facts: the version this JVM runs, the newest published release
 * and whether an update is available, plus whether the process is a container.
 *
 * <p>The assistant can already read a lot of task state but had no way to answer "am I on the
 * latest version / how do I upgrade / is this Docker or the jar" without guessing from the
 * knowledge base. Those answers are install-specific (an intranet install may have the update
 * check switched off), so they belong in a tool.
 *
 * <p>Upgrading itself stays manual: the tool returns the deployment-appropriate steps, and the
 * prompt forbids claiming an upgrade happened.
 */
@Component
public class RuntimeInfoTool implements SupportTool {

    private final VersionService versions;

    public RuntimeInfoTool(VersionService versions) {
        this.versions = versions;
    }

    public String name() {
        return "runtime_info";
    }

    public String description() {
        return "Read-only runtime and release information: running version, newest published "
                + "version/tag and update state (UP_TO_DATE, UPDATE_AVAILABLE, ARTIFACT_UPDATED, "
                + "CHECKING, UNKNOWN, DISABLED), whether this instance runs in a container, and "
                + "the Java / Spring Boot versions. Use for 'am I on the latest version', 'how do "
                + "I upgrade', or 'is this Docker or the jar'. No arguments. The assistant cannot "
                + "upgrade, pull images or restart the process.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    public Set<String> allowedKeys() {
        return Set.of();
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        VersionInfo version = versions.snapshot();
        boolean container = DeploymentMode.isContainer();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentVersion", version.getCurrentVersion());
        out.put("latestVersion", version.getLatestVersion());
        out.put("latestTag", version.getLatestTag());
        out.put("updateState", version.getState() == null ? null : version.getState().name());
        out.put("latestPublishedDate", version.getLatestPublishedDate());
        out.put("releaseUrl", version.getReleaseUrl());
        out.put("deployment", container ? "container" : "jar");
        out.put("javaVersion", System.getProperty("java.version"));
        out.put("springBootVersion", SpringBootVersion.getVersion());
        out.put("upgradeHint", container
                ? "Pull the new image (vfaner/jync:<tag> or latest), then recreate the container; "
                        + "the volume behind /app/data keeps the metadata database and certificates. "
                        + "The assistant cannot pull images or restart containers."
                : "Use 系统信息 → 一键升级, or back up the data directory and replace jync.jar, then "
                        + "restart. The assistant cannot download files or restart the process.");
        out.put("note", "updateState=DISABLED means the update check is switched off in "
                + "系统配置 → 更新检查; UNKNOWN means the release feed could not be reached.");
        return out;
    }
}
