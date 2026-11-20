package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The alert board only shows conditions that are still active. A task that failed at night and
 * recovered before morning is invisible there, yet it is exactly what an operator asks about
 * the next day. This tool reads the failed change-log rows in a bounded time window.
 */
class RecentFailuresToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));
    private final ChangeLogRepository changes = mock(ChangeLogRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);

    private RecentFailuresTool tool() {
        return new RecentFailuresTool(changes, projects);
    }

    private static Project project(long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private static ChangeLog failure(long projectId, String table, String detail, Instant at) {
        ChangeLog row = ChangeLog.of(projectId, ObjectType.TABLE, table, ChangeType.ERROR, detail);
        row.setSuccess(false);
        row.setErrorDetail(detail);
        row.setOccurredAt(at);
        return row;
    }

    private JsonNode run(ObjectNode args) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(tool().execute(args, ctx)));
    }

    @Test
    void listsFailuresWithProjectNamesAndClampedSummaries() throws Exception {
        String longDetail = "x".repeat(300);
        ChangeLog newest = failure(7L, "orders", "ORA-12170: connect timeout",
                Instant.parse("2026-11-20T02:00:00Z"));
        ChangeLog older = failure(8L, "customers", longDetail,
                Instant.parse("2026-11-20T01:00:00Z"));
        when(changes.findTop20BySuccessFalseAndOccurredAtAfterOrderByOccurredAtDesc(any()))
                .thenReturn(List.of(newest, older));
        when(projects.findAllById(any())).thenReturn(List.of(
                project(7L, "订单同步"), project(8L, "客户同步")));

        JsonNode rows = run(mapper.createObjectNode());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("project").asString()).isEqualTo("订单同步");
        assertThat(rows.get(0).path("object").asString()).isEqualTo("orders");
        assertThat(rows.get(0).path("at").asString()).isEqualTo("2026-11-20T02:00:00Z");
        assertThat(rows.get(0).path("summary").asString()).contains("connect timeout");
        assertThat(rows.get(1).path("project").asString()).isEqualTo("客户同步");
        assertThat(rows.get(1).path("summary").asString()).hasSize(200);
    }

    @Test
    void theDefaultWindowIsTheLastDay() throws Exception {
        when(changes.findTop20BySuccessFalseAndOccurredAtAfterOrderByOccurredAtDesc(any()))
                .thenReturn(List.of());
        Instant before = Instant.now();

        run(mapper.createObjectNode());

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(changes).findTop20BySuccessFalseAndOccurredAtAfterOrderByOccurredAtDesc(
                since.capture());
        assertThat(Duration.between(since.getValue(), before).toMinutes())
                .isBetween(23L * 60L, 24L * 60L + 1L);
    }

    @Test
    void aProjectIdNarrowsTheSearchToThatProject() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", 7L);
        when(changes.findTop20ByProjectIdAndSuccessFalseAndOccurredAtAfterOrderByOccurredAtDesc(
                any(), any())).thenReturn(List.of());

        run(args);

        verify(changes).findTop20ByProjectIdAndSuccessFalseAndOccurredAtAfterOrderByOccurredAtDesc(
                org.mockito.ArgumentMatchers.eq(7L), any());
        verify(changes, never())
                .findTop20BySuccessFalseAndOccurredAtAfterOrderByOccurredAtDesc(any());
    }

    @Test
    void anOutOfRangeWindowIsABadArgument() {
        ObjectNode zero = mapper.createObjectNode();
        zero.put("hours", 0);
        ObjectNode tooWide = mapper.createObjectNode();
        tooWide.put("hours", 169);

        assertThatThrownBy(() -> tool().execute(zero, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SupportArgs.BAD_ARGS);
        assertThatThrownBy(() -> tool().execute(tooWide, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SupportArgs.BAD_ARGS);
    }

    @Test
    void noFailuresInTheWindowIsAnEmptyList() throws Exception {
        when(changes.findTop20BySuccessFalseAndOccurredAtAfterOrderByOccurredAtDesc(any()))
                .thenReturn(List.of());

        assertThat(run(mapper.createObjectNode())).isEmpty();
    }
}
