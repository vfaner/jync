package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.settings.SessionTimeoutService;

import tools.jackson.databind.JsonNode;

/**
 * Administrator-only confirmation card for the authenticated-session idle timeout.
 *
 * <p>The value is minutes, at least 30. It is stored in {@code system_setting} and applied to
 * the servlet context; a session that already exists picks it up on its next request.
 */
@Component
public class UpdateSessionTimeoutTool implements SupportTool {

    private static final String NOTE_KEY = "support.action.system.session.note";

    private final SessionTimeoutService timeouts;
    private final SupportActionStore actions;

    public UpdateSessionTimeoutTool(SessionTimeoutService timeouts, SupportActionStore actions) {
        this.timeouts = timeouts;
        this.actions = actions;
    }

    public String name() {
        return "update_session_timeout";
    }

    public String description() {
        return "Administrator-only: prepare a confirmation card to change the idle timeout of "
                + "authenticated login sessions. minutes must be an integer from 30 to 43200 "
                + "(30 days). The value is stored and applied to new sessions immediately; an "
                + "existing session adopts it on its next request. Nothing is written until "
                + "the user confirms the card.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("minutes", Map.of(
                        "type", "integer",
                        "minimum", SessionTimeoutService.MIN_MINUTES,
                        "maximum", SessionTimeoutService.MAX_MINUTES)),
                "required", List.of("minutes"));
    }

    public Set<String> allowedKeys() {
        return Set.of("minutes");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        if (!ctx.actor().isAdmin()) {
            return error("error.accessDenied");
        }
        long requested = SupportArgs.requireLong(args, "minutes");
        if (requested < SessionTimeoutService.MIN_MINUTES
                || requested > SessionTimeoutService.MAX_MINUTES) {
            throw new IllegalArgumentException("error.session.timeout.range");
        }
        int minutes = (int) requested;
        SessionTimeoutService.Change change = timeouts.preview(minutes);

        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("field", SessionTimeoutService.KEY);
        field.put("labelKey", "support.action.system.session.minutes");
        field.put("before", Integer.toString(change.beforeMinutes()));
        field.put("beforeIsDefault", change.beforeIsDefault());
        field.put("after", Integer.toString(change.afterMinutes()));
        field.put("afterIsDefault", false);
        fields.add(field);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("minutes", minutes);
        String token = actions.put(ctx.actor().username(), SupportActionStore.Kind.SYSTEM,
                0L, SupportActionStore.Action.UPDATE_SESSION_TIMEOUT, "system", payload);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("titleKey", SupportActionStore.titleKey(SupportActionStore.Kind.SYSTEM,
                SupportActionStore.Action.UPDATE_SESSION_TIMEOUT));
        out.put("resourceKindKey", "support.action.resource.system");
        out.put("resourceName", "system");
        out.put("needsConfirmation", true);
        out.put("fields", fields);
        out.put("noteKey", NOTE_KEY);
        return out;
    }

    private Map<String, Object> error(String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", key);
        return out;
    }
}
