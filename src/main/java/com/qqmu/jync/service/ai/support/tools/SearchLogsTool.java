package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.log.SystemLogService;

import tools.jackson.databind.JsonNode;

/**
 * Read-only search over Jync's own log files.
 *
 * <p>The bundled knowledge base explains product behaviour; it cannot say what happened in
 * this installation. When a user pastes an error or asks why a task failed, the assistant
 * needs the actual log lines. The service already bounds the scan (tail of the live file,
 * rolled archives, keyword/level filter), so this tool only narrows the output for a prompt.
 */
@Component
public class SearchLogsTool implements SupportTool {

    static final int DEFAULT_LINES = 40;
    static final int MAX_LINES = 100;

    private final SystemLogService logs;

    public SearchLogsTool(SystemLogService logs) {
        this.logs = logs;
    }

    public String name() {
        return "search_logs";
    }

    public String description() {
        return "Search Jync's own application log for a keyword (case-insensitive), optionally "
                + "filtered by level and a rolled file. Returns the newest matching lines, up to "
                + "100. Use it for pasted errors and 'why did this task fail' questions; it is "
                + "read-only and the scan is bounded. Prefer search_docs for product behaviour.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "keyword", Map.of("type", "string"),
                        "level", Map.of("type", "string",
                                "enum", SystemLogService.LEVELS),
                        "file", Map.of("type", "string"),
                        "maxLines", Map.of("type", "integer")),
                "required", List.of("keyword"));
    }

    public Set<String> allowedKeys() {
        return Set.of("keyword", "level", "file", "maxLines");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        String keyword = SupportArgs.requireText(args, "keyword", 200);
        String level = SupportArgs.optionalText(args, "level");
        String file = SupportArgs.optionalText(args, "file");
        Long requested = SupportArgs.optionalLong(args, "maxLines");
        int maxLines = requested == null ? DEFAULT_LINES
                : (int) Math.max(1, Math.min(MAX_LINES, requested));
        if (file == null || file.isBlank()) {
            List<SystemLogService.LogFile> files = logs.listFiles();
            if (files.isEmpty()) {
                return Map.of("available", false,
                        "reason", "no application log file is configured or readable");
            }
            file = files.get(0).getName();
        }
        SystemLogService.LogQueryResult result = logs.query(file, level, keyword);
        if (result.getErrorKey() != null) {
            return Map.of("error", result.getErrorKey());
        }
        List<String> lines = new ArrayList<>();
        for (String line : result.getLines()) {
            // query() renders with HTML escaping and <mark> around the hit; the model needs
            // the original text. The cap keeps one huge stack line from flooding the prompt.
            String plain = HtmlUtils.htmlUnescape(line)
                    .replace("<mark>", "").replace("</mark>", "");
            lines.add(ToolText.safe(plain, 600));
        }
        if (lines.size() > maxLines) {
            lines = lines.subList(lines.size() - maxLines, lines.size());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", file);
        out.put("level", level == null ? "ALL" : level);
        out.put("keyword", keyword);
        out.put("matched", result.getMatched());
        out.put("truncated", result.isTruncated() || result.getLines().size() > maxLines);
        out.put("lines", lines);
        return out;
    }
}
