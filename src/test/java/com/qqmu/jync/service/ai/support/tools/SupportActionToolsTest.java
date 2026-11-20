package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.etl.EtlTaskService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The control tools must never execute anything: they prepare a single-use confirmation token
 * after applying exactly the same ownership rule the buttons do.
 */
class SupportActionToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SupportActionStore store = new SupportActionStore();

    private static Project project(long id, String name, String owner) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setOwnerUsername(owner);
        return p;
    }

    private static EtlTask etlTask(long id, String name, String owner, Long batchId) {
        EtlTask t = new EtlTask();
        t.setId(id);
        t.setName(name);
        t.setOwnerUsername(owner);
        t.setBatchId(batchId);
        return t;
    }

    private ObjectNode args(String field, long id, String action) {
        ObjectNode node = mapper.createObjectNode();
        node.put(field, id);
        node.put("action", action);
        return node;
    }

    private ObjectNode out(Object value) throws Exception {
        return (ObjectNode) mapper.readTree(mapper.writeValueAsString(value));
    }

    @Test
    void anOperatorGetsAConfirmationTokenForItsOwnProjectWithoutRunningIt() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "订单同步", "ops1")));

        var tool = new ControlProjectTool(projects, store);
        var result = out(tool.execute(args("projectId", 7L, "START"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("token").asString()).isNotBlank();
        assertThat(result.path("titleKey").asString()).isEqualTo("support.action.project.start");
        assertThat(result.path("resourceKindKey").asString())
                .isEqualTo("support.action.resource.project");
        assertThat(result.path("resourceName").asString()).isEqualTo("订单同步");
        // The tool only prepared the action; nothing has been started.
        verify(projects, never()).start(any(), any());
        verify(projects, never()).stop(any(), any());
        verify(projects, never()).syncNow(any(), any());
    }

    @Test
    void anOperatorCannotPrepareAnActionOnAnotherOperatorsProject() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "订单同步", "ops1")));

        var tool = new ControlProjectTool(projects, store);
        var result = out(tool.execute(args("projectId", 7L, "STOP"),
                new ToolContext(AccessGuard.of("ops2", UserRole.OPS))));

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").asString()).isEqualTo("error.accessDenied");
        assertThat(result.path("token").isMissingNode()).isTrue();
    }

    @Test
    void aViewerCannotPrepareAnyAction() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "订单同步", "view1")));

        var tool = new ControlProjectTool(projects, store);
        var result = out(tool.execute(args("projectId", 7L, "SYNC_NOW"),
                new ToolContext(AccessGuard.of("view1", UserRole.VIEWER))));

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").asString()).isEqualTo("error.accessDenied");
    }

    @Test
    void anAdminMayPrepareAnActionOnAnyProject() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "订单同步", "ops1")));

        var tool = new ControlProjectTool(projects, store);
        var result = out(tool.execute(args("projectId", 7L, "STOP"),
                new ToolContext(AccessGuard.of("admin", UserRole.ADMIN))));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("token").asString()).isNotBlank();
    }

    @Test
    void aMissingProjectIsReportedNotGuessed() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        when(projects.findById(404L)).thenReturn(Optional.empty());

        var tool = new ControlProjectTool(projects, store);
        var result = out(tool.execute(args("projectId", 404L, "START"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("error").asString()).isEqualTo("error.project.not.found");
    }

    @Test
    void anUnknownActionIsRejectedAsBadArguments() {
        var tool = new ControlProjectTool(mock(ProjectService.class), store);

        assertThatThrownBy(() -> tool.execute(args("projectId", 7L, "DELETE"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.support.tool.args");
    }

    @Test
    void anEtlTaskInABatchCannotBeEnabledOrDisabledIndividually() throws Exception {
        EtlTaskService tasks = mock(EtlTaskService.class);
        when(tasks.findById(3L)).thenReturn(Optional.of(etlTask(3L, "日汇总", "ops1", 9L)));

        var tool = new ControlEtlTaskTool(tasks, store);
        var result = out(tool.execute(args("taskId", 3L, "START"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").asString()).isEqualTo("error.etl.batch.managed");
    }

    @Test
    void anOperatorCanPrepareARunNowOnItsOwnTask() throws Exception {
        EtlTaskService tasks = mock(EtlTaskService.class);
        when(tasks.findById(3L)).thenReturn(Optional.of(etlTask(3L, "日汇总", "ops1", null)));

        var tool = new ControlEtlTaskTool(tasks, store);
        var result = out(tool.execute(args("taskId", 3L, "RUN_NOW"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("titleKey").asString()).isEqualTo("support.action.etl_task.run_now");
        assertThat(result.path("resourceKindKey").asString())
                .isEqualTo("support.action.resource.etl_task");
        verify(tasks, never()).submit(any());
    }

    @Test
    void anOperatorCannotPrepareAnActionOnAnotherOperatorsEtlTask() throws Exception {
        EtlTaskService tasks = mock(EtlTaskService.class);
        when(tasks.findById(3L)).thenReturn(Optional.of(etlTask(3L, "日汇总", "ops1", null)));

        var tool = new ControlEtlTaskTool(tasks, store);
        var result = out(tool.execute(args("taskId", 3L, "RUN_NOW"),
                new ToolContext(AccessGuard.of("ops2", UserRole.OPS))));

        assertThat(result.path("error").asString()).isEqualTo("error.accessDenied");
    }
}
