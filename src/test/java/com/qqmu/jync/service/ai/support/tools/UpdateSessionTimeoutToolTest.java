package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.settings.SessionTimeoutService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class UpdateSessionTimeoutToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SupportActionStore actions = new SupportActionStore();
    private final SessionTimeoutService timeouts = mock(SessionTimeoutService.class);
    private final ToolContext admin = new ToolContext(AccessGuard.of("admin", UserRole.ADMIN));
    private final ToolContext ops = new ToolContext(AccessGuard.of("ops", UserRole.OPS));

    @Test
    void preparesAConfirmationCardWithBeforeAndAfter() {
        when(timeouts.preview(720)).thenReturn(
                new SessionTimeoutService.Change(480, 720, true));
        UpdateSessionTimeoutTool tool = new UpdateSessionTimeoutTool(timeouts, actions);
        ObjectNode args = mapper.createObjectNode().put("minutes", 720);

        String json = mapper.writeValueAsString(tool.execute(args, admin));

        assertThat(json).contains("\"titleKey\":\"support.action.system.update_session_timeout\"")
                .contains("\"labelKey\":\"support.action.system.session.minutes\"")
                .contains("\"before\":\"480\"")
                .contains("\"after\":\"720\"");
        var result = (Map<?, ?>) mapper.readValue(json, Map.class);
        var pending = actions.take((String) result.get("token"), "admin").orElseThrow();
        assertThat(pending.action()).isEqualTo(SupportActionStore.Action.UPDATE_SESSION_TIMEOUT);
        assertThat(pending.payload()).containsEntry("minutes", 720);
    }

    @Test
    void validatesTheMinimumBeforePreparingACard() {
        UpdateSessionTimeoutTool tool = new UpdateSessionTimeoutTool(timeouts, actions);
        ObjectNode args = mapper.createObjectNode().put("minutes", 29);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.execute(args, admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.session.timeout.range");
    }

    @Test
    void onlyAnAdministratorCanPrepareTheCard() {
        UpdateSessionTimeoutTool tool = new UpdateSessionTimeoutTool(timeouts, actions);
        ObjectNode args = mapper.createObjectNode().put("minutes", 720);

        assertThat(mapper.writeValueAsString(tool.execute(args, ops)))
                .contains("error.accessDenied")
                .doesNotContain("\"token\"");
    }
}
