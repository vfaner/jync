package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.sync.SyncStateWriter;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ProjectDdlTasksToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));
    private final ChangeLogRepository changes = mock(ChangeLogRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);

    private ProjectDdlTasksTool tool() {
        return new ProjectDdlTasksTool(changes, projects);
    }

    @Test
    void listsPendingAndFailedSkippedDdlTasksWithTheReplayState() throws Exception {
        Project project = new Project();
        project.setId(7L);
        project.setName("orders");
        when(projects.findById(7L)).thenReturn(Optional.of(project));

        ChangeLog pending = ChangeLog.of(7L, ObjectType.TABLE, "orders", ChangeType.ERROR,
                SyncStateWriter.SKIPPED_DDL_PREFIX + "ALTER TABLE orders ADD COLUMN note INT");
        pending.setId(3L);
        pending.setResolutionStatus(ChangeLog.RESOLUTION_PENDING);
        pending.setDdlExecuted(true);
        pending.setReplayDone(false);
        pending.setTargetDdlSql("ALTER TABLE orders ADD COLUMN note INT");
        pending.setAffectedTables("orders(note)");
        pending.setGapEndFile("mysql-bin.000123");
        pending.setGapEndPos(456L);
        pending.setOccurredAt(Instant.parse("2026-11-26T10:00:00Z"));

        ChangeLog failed = ChangeLog.of(7L, ObjectType.TABLE, "customers", ChangeType.ERROR,
                SyncStateWriter.SKIPPED_DDL_PREFIX + "ALTER TABLE customers MODIFY name TEXT");
        failed.setId(4L);
        failed.setResolutionStatus(ChangeLog.RESOLUTION_FAILED);
        failed.setErrorDetail("ORA-00904: invalid identifier");
        failed.setOccurredAt(Instant.parse("2026-11-26T11:00:00Z"));

        PageRequest page = PageRequest.of(0, ProjectDdlTasksTool.LIMIT);
        when(changes.findByProjectIdAndResolutionStatusOrderByOccurredAtDesc(
                eq(7L), eq(ChangeLog.RESOLUTION_PENDING), any())).thenReturn(
                new PageImpl<>(List.of(pending), page, 1));
        when(changes.findByProjectIdAndResolutionStatusOrderByOccurredAtDesc(
                eq(7L), eq(ChangeLog.RESOLUTION_FAILED), any())).thenReturn(
                new PageImpl<>(List.of(failed), page, 1));

        ObjectNode args = mapper.createObjectNode().put("projectId", 7L);
        var rows = mapper.readTree(mapper.writeValueAsString(tool().execute(args, ctx)));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("id").asLong()).isEqualTo(4L);
        assertThat(rows.get(0).get("status").asString()).isEqualTo(ChangeLog.RESOLUTION_FAILED);
        assertThat(rows.get(1).get("id").asLong()).isEqualTo(3L);
        assertThat(rows.get(1).get("ddlExecuted").asBoolean()).isTrue();
        assertThat(rows.get(1).get("replayDone").asBoolean()).isFalse();
        assertThat(rows.get(1).get("affectedTables").asString()).isEqualTo("orders(note)");
        assertThat(rows.get(1).get("gapEnd").asString()).isEqualTo("mysql-bin.000123:456");
    }

    @Test
    void aMissingProjectIsReportedWithoutLeakingAFailedQuery() throws Exception {
        when(projects.findById(9L)).thenReturn(Optional.empty());
        ObjectNode args = mapper.createObjectNode().put("projectId", 9L);

        var result = mapper.readTree(mapper.writeValueAsString(tool().execute(args, ctx)));

        assertThat(result.get("error").asString()).isEqualTo("error.project.not.found");
    }
}
