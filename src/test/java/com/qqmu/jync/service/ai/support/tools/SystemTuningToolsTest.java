package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.settings.SystemTuningService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SystemTuningToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SupportActionStore actions = new SupportActionStore();
    private final SystemTuningService tuning = mock(SystemTuningService.class);
    private final ToolContext admin = new ToolContext(AccessGuard.of("admin", UserRole.ADMIN));
    private final ToolContext ops = new ToolContext(AccessGuard.of("ops1", UserRole.OPS));

    @Test
    void theReadToolIsAdminOnlyAndReturnsTheTuningItems() {
        when(tuning.items()).thenReturn(List.of(new SystemTuningService.Item(
                "sync.batch-size", "1000", "500", true, "INT", 1L, 20_000L)));
        var tool = new SystemParamsTool(tuning);

        String json = mapper.writeValueAsString(tool.execute(mapper.createObjectNode(), admin));
        assertThat(json).contains("\"key\":\"sync.batch-size\"")
                .contains("\"value\":\"1000\"")
                .contains("\"base\":\"500\"");
        assertThat(mapper.writeValueAsString(tool.execute(mapper.createObjectNode(), ops)))
                .contains("error.accessDenied");
    }

    @Test
    void theUpdateToolPreparesACardWithOldNewAndResetSemantics() {
        when(tuning.preview(Map.of("sync.batch-size", "1000"),
                List.of("sync.chunk-size"))).thenReturn(List.of(
                new SystemTuningService.Change(
                        SystemTuningService.Tunable.BATCH_SIZE, "500", "1000", true, false),
                new SystemTuningService.Change(
                        SystemTuningService.Tunable.CHUNK_SIZE, "2000", "100000", false, true)));
        var tool = new UpdateSystemParamsTool(tuning, actions);
        ObjectNode args = mapper.createObjectNode();
        args.putObject("set").put("sync.batch-size", "1000");
        args.putArray("reset").add("sync.chunk-size");

        String json = mapper.writeValueAsString(tool.execute(args, admin));

        assertThat(json).contains("\"titleKey\":\"support.action.system.update_params\"")
                .contains("\"labelKey\":\"sync.batch-size\"")
                .contains("\"before\":\"500\"")
                .contains("\"after\":\"1000\"")
                .contains("\"labelKey\":\"sync.chunk-size\"")
                .contains("\"afterIsDefault\":true");
        var result = (Map<?, ?>) mapper.readValue(json, Map.class);
        var pending = actions.take((String) result.get("token"), "admin").orElseThrow();
        assertThat(pending.kind()).isEqualTo(SupportActionStore.Kind.SYSTEM);
        assertThat(pending.action()).isEqualTo(SupportActionStore.Action.UPDATE_PARAMS);
        @SuppressWarnings("unchecked")
        Map<String, Object> set = (Map<String, Object>) pending.payload().get("set");
        assertThat(set).containsEntry("sync.batch-size", "1000");
        @SuppressWarnings("unchecked")
        List<Object> reset = (List<Object>) pending.payload().get("reset");
        assertThat(reset).containsExactly("sync.chunk-size");
    }

    @Test
    void onlyAnAdministratorCanPrepareTheCard() {
        var tool = new UpdateSystemParamsTool(tuning, actions);
        ObjectNode args = mapper.createObjectNode();
        args.putObject("set").put("sync.batch-size", "1000");

        assertThat(mapper.writeValueAsString(tool.execute(args, ops)))
                .contains("error.accessDenied")
                .doesNotContain("\"token\"");
    }
}
