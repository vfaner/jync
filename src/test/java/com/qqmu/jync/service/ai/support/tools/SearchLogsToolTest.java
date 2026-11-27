package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.log.SystemLogService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SearchLogsToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));

    @Test
    void findsAnErrorLineInTheLiveLog(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("jync.log");
        Files.writeString(log, """
                2026-11-27 10:00:00.000 INFO  [main] c.q.j.SyncTask - started
                2026-11-27 10:00:01.000 ERROR [main] c.q.j.Cdc - ORA-01291: missing logfile
                2026-11-27 10:00:02.000 INFO  [main] c.q.j.SyncTask - finished
                """);
        SearchLogsTool tool = new SearchLogsTool(new SystemLogService(log.toString()));

        ObjectNode args = mapper.createObjectNode()
                .put("keyword", "ORA-01291").put("level", "ERROR");
        var result = mapper.readTree(mapper.writeValueAsString(tool.execute(args, ctx)));

        assertThat(result.path("file").asString()).isEqualTo("jync.log");
        assertThat(result.path("matched").asInt()).isEqualTo(1);
        assertThat(result.path("lines").get(0).asString()).contains("ORA-01291: missing logfile");
    }

    @Test
    void aLiteralMarkTagInTheLogIsNotMistakenForTheHighlightMarker(@TempDir Path dir)
            throws Exception {
        Path log = dir.resolve("jync.log");
        Files.writeString(log,
                "2026-11-27 10:00:01.000 ERROR [main] c.q.j.Cdc - payload <mark>literal</mark>\n");
        SearchLogsTool tool = new SearchLogsTool(new SystemLogService(log.toString()));

        ObjectNode args = mapper.createObjectNode()
                .put("keyword", "ERROR").put("level", "ERROR");
        var result = mapper.readTree(mapper.writeValueAsString(tool.execute(args, ctx)));

        assertThat(result.path("lines").get(0).asString())
                .contains("payload <mark>literal</mark>");
    }

    @Test
    void aMissingFileIsReportedAsAToolError(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("jync.log");
        Files.writeString(log, "2026-11-27 10:00:00 INFO [main] x - ok\n");
        SearchLogsTool tool = new SearchLogsTool(new SystemLogService(log.toString()));

        ObjectNode args = mapper.createObjectNode()
                .put("keyword", "x").put("file", "../etc/passwd");
        var result = mapper.readTree(mapper.writeValueAsString(tool.execute(args, ctx)));

        assertThat(result.path("error").asString()).startsWith("syslog.error");
    }
}
