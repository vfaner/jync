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

import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.migration.MigrationTaskService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The migration control tool must never run anything: it prepares a single-use confirmation
 * token after the same ownership check the page buttons apply, and refuses a card that would
 * dead-end at the click (no cron to enable, a run already in flight, nothing to cancel).
 */
class ControlMigrationToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SupportActionStore store = new SupportActionStore();
    private final MigrationTaskService tasks = mock(MigrationTaskService.class);

    private static MigrationTask task(long id, String name, String owner, String cron) {
        MigrationTask task = new MigrationTask();
        task.setId(id);
        task.setName(name);
        task.setType(MigrationType.ES_TO_EASYSEARCH);
        task.setOwnerUsername(owner);
        task.setCronExpression(cron);
        return task;
    }

    private ObjectNode args(long id, String action) {
        ObjectNode node = mapper.createObjectNode();
        node.put("taskId", id);
        node.put("action", action);
        return node;
    }

    private ObjectNode out(Object value) throws Exception {
        return (ObjectNode) mapper.readTree(mapper.writeValueAsString(value));
    }

    private ControlMigrationTool tool() {
        return new ControlMigrationTool(tasks, store);
    }

    @Test
    void anOperatorCanPrepareARunNowOnItsOwnTaskWithoutStartingIt() throws Exception {
        when(tasks.findById(5L)).thenReturn(Optional.of(task(5L, "es-orders", "ops1",
                "0 0 3 * * *")));
        when(tasks.isRunning(5L)).thenReturn(false);

        ObjectNode result = out(tool().execute(args(5L, "RUN_NOW"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("token").asString()).isNotBlank();
        assertThat(result.path("titleKey").asString())
                .isEqualTo("support.action.migration_task.run_now");
        assertThat(result.path("resourceKindKey").asString())
                .isEqualTo("support.action.resource.migration_task");
        assertThat(result.path("resourceName").asString()).isEqualTo("es-orders");
        assertThat(result.path("needsConfirmation").asBoolean()).isTrue();
        verify(tasks, never()).submit(any(), any());
        verify(tasks, never()).setEnabled(any(), any(Boolean.class), any());
        verify(tasks, never()).cancel(any(), any());
    }

    @Test
    void enablingWithoutACronIsRefusedBeforeADeadEndCardIsShown() throws Exception {
        when(tasks.findById(5L)).thenReturn(Optional.of(task(5L, "es-orders", "ops1", null)));

        ObjectNode result = out(tool().execute(args(5L, "START"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").asString()).isEqualTo("error.migration.cron.required");
    }

    @Test
    void enablingWithAnInvalidCronIsRefusedBeforeADeadEndCardIsShown() throws Exception {
        when(tasks.findById(5L)).thenReturn(Optional.of(task(5L, "es-orders", "ops1",
                "not a cron")));

        ObjectNode result = out(tool().execute(args(5L, "START"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("error").asString()).isEqualTo("error.migration.cron.invalid");
    }

    @Test
    void aRunNowOnATaskAlreadyRunningIsRefused() throws Exception {
        when(tasks.findById(5L)).thenReturn(Optional.of(task(5L, "es-orders", "ops1",
                "0 0 3 * * *")));
        when(tasks.isRunning(5L)).thenReturn(true);

        ObjectNode result = out(tool().execute(args(5L, "RUN_NOW"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("error").asString()).isEqualTo("error.migration.running");
    }

    @Test
    void cancellingIdleTaskIsRefused() throws Exception {
        when(tasks.findById(5L)).thenReturn(Optional.of(task(5L, "es-orders", "ops1",
                "0 0 3 * * *")));
        when(tasks.isRunning(5L)).thenReturn(false);

        ObjectNode result = out(tool().execute(args(5L, "CANCEL"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("error").asString()).isEqualTo("error.migration.not.running");
    }

    @Test
    void cancellingARunningTaskIsPreparedAsACard() throws Exception {
        when(tasks.findById(5L)).thenReturn(Optional.of(task(5L, "es-orders", "ops1",
                "0 0 3 * * *")));
        when(tasks.isRunning(5L)).thenReturn(true);

        ObjectNode result = out(tool().execute(args(5L, "CANCEL"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("titleKey").asString())
                .isEqualTo("support.action.migration_task.cancel");
        verify(tasks, never()).cancel(any(), any());
    }

    @Test
    void anOperatorCannotPrepareAnActionOnAnotherOperatorsTask() throws Exception {
        when(tasks.findById(5L)).thenReturn(Optional.of(task(5L, "es-orders", "ops1",
                "0 0 3 * * *")));

        ObjectNode result = out(tool().execute(args(5L, "STOP"),
                new ToolContext(AccessGuard.of("ops2", UserRole.OPS))));

        assertThat(result.path("error").asString()).isEqualTo("error.accessDenied");
    }

    @Test
    void aViewerCannotPrepareAnyAction() throws Exception {
        when(tasks.findById(5L)).thenReturn(Optional.of(task(5L, "es-orders", "view1",
                "0 0 3 * * *")));

        ObjectNode result = out(tool().execute(args(5L, "STOP"),
                new ToolContext(AccessGuard.of("view1", UserRole.VIEWER))));

        assertThat(result.path("error").asString()).isEqualTo("error.accessDenied");
    }

    @Test
    void anAdminMayPrepareAnActionOnAnyTask() throws Exception {
        when(tasks.findById(5L)).thenReturn(Optional.of(task(5L, "es-orders", "ops1",
                "0 0 3 * * *")));

        ObjectNode result = out(tool().execute(args(5L, "STOP"),
                new ToolContext(AccessGuard.of("admin", UserRole.ADMIN))));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("titleKey").asString())
                .isEqualTo("support.action.migration_task.stop");
    }

    @Test
    void aMissingTaskIsReportedNotGuessed() throws Exception {
        when(tasks.findById(404L)).thenReturn(Optional.empty());

        ObjectNode result = out(tool().execute(args(404L, "RUN_NOW"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))));

        assertThat(result.path("error").asString()).isEqualTo("error.migration.not.found");
    }

    @Test
    void anUnknownActionIsRejectedAsBadArguments() {
        assertThatThrownBy(() -> tool().execute(args(5L, "DELETE"),
                new ToolContext(AccessGuard.of("ops1", UserRole.OPS))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.support.tool.args");
    }

    @Test
    void theDescriptionNamesTheParametersTheSchemaDeclares() {
        assertThat(tool().description()).contains("taskId").contains("action");
        assertThat(tool().allowedKeys()).containsExactlyInAnyOrder("taskId", "action");
        assertThat(tool().parameters().toString()).contains("CANCEL");
    }
}
