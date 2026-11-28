package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ProjectTableStatusToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));

    private ProjectTableStatusTool tool(List<ProjectService.TableSyncStatus> rows) {
        ProjectService projects = mock(ProjectService.class);
        Project project = new Project();
        project.setId(7L);
        project.setName("orders");
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setTables(rows.stream().map(ProjectService.TableSyncStatus::name)
                .collect(java.util.stream.Collectors.toCollection(
                        java.util.LinkedHashSet::new)));
        when(projects.loadConfig(any())).thenReturn(config);
        when(projects.isRunning(any())).thenReturn(true);
        when(projects.findTableSyncStatuses(eq(7L), any())).thenReturn(rows);
        return new ProjectTableStatusTool(projects);
    }

    private ObjectNode args() {
        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", 7);
        return args;
    }

    @Test
    void reportsCountsAndEachTableStateIncludingNeverStarted() throws Exception {
        Instant synced = Instant.parse("2026-11-24T10:00:00Z");
        var tool = tool(List.of(
                new ProjectService.TableSyncStatus("ORDERS", ProjectService.SyncState.COMPLETED,
                        120L, synced, null, null),
                new ProjectService.TableSyncStatus("ORDER_ITEMS",
                        ProjectService.SyncState.IN_PROGRESS, 10L, null, 500L, synced),
                new ProjectService.TableSyncStatus("OLD_LINES", ProjectService.SyncState.INCOMPLETE,
                        3L, null, null, null),
                new ProjectService.TableSyncStatus("AUDIT", ProjectService.SyncState.NOT_STARTED,
                        null, null, null, null)));

        String json = mapper.writeValueAsString(tool.execute(args(), ctx));

        assertThat(json).contains("\"selected\":4")
                .contains("\"selectionKnown\":true")
                .contains("\"completed\":1")
                .contains("\"inProgress\":1")
                .contains("\"incomplete\":1")
                .contains("\"notStarted\":1")
                .contains("\"table\":\"AUDIT\"")
                .contains("\"state\":\"NOT_STARTED\"")
                .contains("\"state\":\"IN_PROGRESS\"")
                .contains("\"rowsSyncedTotal\":120")
                .contains("\"fullLoadRowsCopied\":500");
    }

    @Test
    void filtersByTableSubstringCaseInsensitivelyAndByState() throws Exception {
        var tool = tool(List.of(
                new ProjectService.TableSyncStatus("ORDERS", ProjectService.SyncState.COMPLETED,
                        null, null, null, null),
                new ProjectService.TableSyncStatus("ORDER_ITEMS",
                        ProjectService.SyncState.IN_PROGRESS, null, null, 5L, null),
                new ProjectService.TableSyncStatus("AUDIT", ProjectService.SyncState.NOT_STARTED,
                        null, null, null, null)));

        ObjectNode args = args();
        args.put("table", "order");
        args.put("state", "IN_PROGRESS");
        String json = mapper.writeValueAsString(tool.execute(args, ctx));

        assertThat(json).contains("\"table\":\"ORDER_ITEMS\"")
                .doesNotContain("\"table\":\"ORDERS\"")
                .doesNotContain("\"table\":\"AUDIT\"")
                .contains("\"matched\":1")
                .contains("\"truncated\":false");

        // The summary always describes the whole project, so "how many have not started"
        // is still answerable while a filter is active.
        assertThat(json).contains("\"notStarted\":1");
    }

    @Test
    void anUnknownTableReturnsRealNamesToAskAbout() throws Exception {
        var tool = tool(List.of(
                new ProjectService.TableSyncStatus("ORDERS", ProjectService.SyncState.COMPLETED,
                        null, null, null, null),
                new ProjectService.TableSyncStatus("AUDIT", ProjectService.SyncState.NOT_STARTED,
                        null, null, null, null)));

        ObjectNode args = args();
        args.put("table", "T_ORDER");
        String json = mapper.writeValueAsString(tool.execute(args, ctx));

        assertThat(json).contains("\"matched\":0")
                .contains("\"tables\":[]")
                .contains("\"knownTables\":[\"ORDERS\",\"AUDIT\"]");
    }

    @Test
    void aLargeProjectIsCappedAndFlaggedAsTruncated() throws Exception {
        List<ProjectService.TableSyncStatus> rows = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> new ProjectService.TableSyncStatus("T" + i,
                        ProjectService.SyncState.COMPLETED, 1L, null, null, null))
                .toList();
        var tool = tool(rows);

        ObjectNode args = args();
        args.put("limit", 5);
        String json = mapper.writeValueAsString(tool.execute(args, ctx));

        assertThat(json).contains("\"matched\":30")
                .contains("\"truncated\":true");
        assertThat(json.split("\"state\":\"COMPLETED\"", -1)).hasSize(6); // five rows
    }

    @Test
    void rejectsAnUnknownStateInsteadOfReturningEveryTable() {
        var tool = tool(List.of());

        ObjectNode args = args();
        args.put("state", "DONE");

        assertThatThrownBy(() -> tool.execute(args, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SupportArgs.BAD_ARGS);
    }

    @Test
    void aMissingProjectIsReportedAsSuch() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        when(projects.findById(7L)).thenReturn(Optional.empty());
        var tool = new ProjectTableStatusTool(projects);

        assertThat(mapper.writeValueAsString(tool.execute(args(), ctx)))
                .contains("error.project.not.found");
    }
}
