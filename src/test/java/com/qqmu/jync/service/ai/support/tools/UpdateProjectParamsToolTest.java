package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class UpdateProjectParamsToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SupportActionStore actions = new SupportActionStore();
    private final ProjectService projects = mock(ProjectService.class);
    private final AccessGuard ops = AccessGuard.of("ops1", UserRole.OPS);
    private final ToolContext ctx = new ToolContext(ops);

    private Project project(String owner, boolean enabled) {
        Project project = new Project();
        project.setId(7L);
        project.setName("orders");
        project.setOwnerUsername(owner);
        project.setEnabled(enabled);
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);
        config.setBatchSize(500);
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        when(projects.loadConfig(project)).thenReturn(config);
        when(projects.isRunning(project)).thenReturn(enabled);
        return project;
    }

    private ObjectNode args() {
        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", 7);
        ObjectNode changes = args.putObject("changes");
        changes.put("batchSize", 1000);
        changes.put("fullLoadParallelism", 4);
        return args;
    }

    @Test
    void preparesACardWithOldAndNewValuesAndStoresTheWhitelistedPayload() throws Exception {
        project("ops1", false);
        var tool = new UpdateProjectParamsTool(projects, actions);

        String json = mapper.writeValueAsString(tool.execute(args(), ctx));

        assertThat(json).contains("\"ok\":true")
                .contains("\"titleKey\":\"support.action.project.update_config\"")
                .contains("\"field\":\"batchSize\"")
                .contains("\"before\":500")
                .contains("\"after\":1000")
                .contains("\"noteKey\":\"support.action.config.note\"");

        @SuppressWarnings("unchecked")
        var result = (java.util.Map<String, Object>) mapper.readValue(json, java.util.Map.class);
        String token = (String) result.get("token");
        var pending = actions.take(token, "ops1").orElseThrow();
        assertThat(pending.action()).isEqualTo(SupportActionStore.Action.UPDATE_CONFIG);
        assertThat(pending.payload()).containsEntry("batchSize", 1000L)
                .containsEntry("fullLoadParallelism", 4L);
    }

    @Test
    void aRunningProjectGetsAnExplanationInsteadOfACardThatWouldFail() throws Exception {
        project("ops1", true);
        var tool = new UpdateProjectParamsTool(projects, actions);

        String json = mapper.writeValueAsString(tool.execute(args(), ctx));
        assertThat(json).contains("\"ok\":false")
                .contains("error.project.config.running")
                .doesNotContain("\"token\"");
    }

    @Test
    void anotherOperatorsProjectIsRefusedBeforeAnyCardExists() throws Exception {
        project("alice", false);
        var tool = new UpdateProjectParamsTool(projects, actions);

        String json = mapper.writeValueAsString(tool.execute(args(), ctx));
        assertThat(json).contains("error.accessDenied")
                .doesNotContain("\"token\"");
    }

    @Test
    void nonWhitelistedOrNonIntegralFieldsAreRefused() {
        project("ops1", false);
        var tool = new UpdateProjectParamsTool(projects, actions);

        ObjectNode forbidden = mapper.createObjectNode();
        forbidden.put("projectId", 7);
        forbidden.putObject("changes").put("syncDeletes", true);
        assertThatThrownBy(() -> tool.execute(forbidden, ctx))
                .hasMessage("error.support.param.notAllowed");

        ObjectNode fractional = mapper.createObjectNode();
        fractional.put("projectId", 7);
        fractional.putObject("changes").put("batchSize", 1.5);
        assertThatThrownBy(() -> tool.execute(fractional, ctx))
                .hasMessage("error.support.param.invalid");
    }

    @Test
    void aStrategyThatDoesNotUseTheFieldIsRefused() {
        Project project = project("ops1", false);
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        when(projects.loadConfig(project)).thenReturn(config);
        var tool = new UpdateProjectParamsTool(projects, actions);

        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", 7);
        args.putObject("changes").put("pollIntervalMs", 2000);

        assertThatThrownBy(() -> tool.execute(args, ctx))
                .hasMessage("error.support.param.strategy");
    }
}
