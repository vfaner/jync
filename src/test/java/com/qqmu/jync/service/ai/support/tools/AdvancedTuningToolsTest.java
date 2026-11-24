package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Cursor-column, cron and connection-parameter cards. */
class AdvancedTuningToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SupportActionStore actions = new SupportActionStore();
    private final ProjectService projects = mock(ProjectService.class);
    private final DatabaseConfigService databases = mock(DatabaseConfigService.class);
    private final AccessGuard ops = AccessGuard.of("ops1", UserRole.OPS);
    private final ToolContext ctx = new ToolContext(ops);

    private SyncConfig project(SyncConfig config, boolean running) {
        Project project = new Project();
        project.setId(7L);
        project.setName("orders");
        project.setOwnerUsername("ops1");
        project.setEnabled(running);
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        when(projects.loadConfig(project)).thenReturn(config);
        when(projects.isRunning(project)).thenReturn(running);
        return config;
    }

    private SyncConfig pollingConfig() {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);
        config.setTables(new java.util.LinkedHashSet<>(List.of("ORDERS", "ITEMS")));
        return config;
    }

    private ObjectNode projectArgs(long id) {
        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", id);
        return args;
    }

    // --- cursor ---------------------------------------------------------------------------

    @Test
    void cursorColumnsAndPriorityProduceOneCardAndOnlyTheChangedPayload() throws Exception {
        project(pollingConfig(), false);
        var tool = new UpdateProjectCursorTool(projects, actions);
        ObjectNode args = projectArgs(7);
        args.putObject("columns").put("ORDERS", "UPDATED_AT");
        args.putArray("priority").add("UPDATE_TIME").add("ID");

        String json = mapper.writeValueAsString(tool.execute(args, ctx));

        assertThat(json).contains("\"titleKey\":\"support.action.project.update_cursor\"")
                .contains("\"labelKey\":\"ORDERS\"")
                .contains("\"after\":\"UPDATED_AT\"")
                .contains("\"labelKey\":\"cursor.priority.title\"");
        var result = (Map<?, ?>) mapper.readValue(json, Map.class);
        var pending = actions.take((String) result.get("token"), "ops1").orElseThrow();
        assertThat(pending.action()).isEqualTo(SupportActionStore.Action.UPDATE_CURSOR);
        @SuppressWarnings("unchecked")
        Map<String, Object> columns = (Map<String, Object>) pending.payload().get("columns");
        assertThat(columns).containsEntry("ORDERS", "UPDATED_AT");
        @SuppressWarnings("unchecked")
        List<Object> priority = (List<Object>) pending.payload().get("priority");
        assertThat(priority).containsExactly("UPDATE_TIME", "ID");
    }

    @Test
    void cursorColumnsAreRefusedForLogCdcAndWhileRunning() throws Exception {
        SyncConfig binlog = pollingConfig();
        binlog.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        project(binlog, false);
        var tool = new UpdateProjectCursorTool(projects, actions);
        ObjectNode args = projectArgs(7);
        args.putObject("columns").put("ORDERS", "ID");
        assertThatThrownBy(() -> tool.execute(args, ctx))
                .hasMessage("error.project.cursor.not.polling");

        project(pollingConfig(), true);
        assertThat(mapper.writeValueAsString(tool.execute(args, ctx)))
                .contains("error.project.config.running")
                .doesNotContain("\"token\"");
    }

    @Test
    void anUnknownTableIsRefusedInsteadOfStoringAnUnusedOverride() throws Exception {
        project(pollingConfig(), false);
        var tool = new UpdateProjectCursorTool(projects, actions);
        ObjectNode args = projectArgs(7);
        args.putObject("columns").put("GHOST", "ID");

        assertThatThrownBy(() -> tool.execute(args, ctx))
                .hasMessage("error.project.cursor.table.notSelected");
    }

    // --- schedule -------------------------------------------------------------------------

    @Test
    void aCronCardAndABlankCronForClearingAreBothSupported() throws Exception {
        project(pollingConfig(), false);
        var tool = new UpdateProjectScheduleTool(projects, actions);

        ObjectNode set = projectArgs(7);
        set.put("cronExpression", "0 0/5 * * * ?");
        String json = mapper.writeValueAsString(tool.execute(set, ctx));
        assertThat(json).contains("\"beforeIsDefault\":true")
                .contains("\"after\":\"0 0/5 * * * ?\"");
        var result = (Map<?, ?>) mapper.readValue(json, Map.class);
        var pending = actions.take((String) result.get("token"), "ops1").orElseThrow();
        assertThat(pending.action()).isEqualTo(SupportActionStore.Action.UPDATE_SCHEDULE);

        SyncConfig existing = pollingConfig();
        existing.setCronExpression("0 0/5 * * * ?");
        project(existing, false);
        ObjectNode clear = projectArgs(7);
        clear.put("cronExpression", "");
        String cleared = mapper.writeValueAsString(tool.execute(clear, ctx));
        assertThat(cleared).contains("\"afterIsDefault\":true");
    }

    @Test
    void anInvalidCronAndALogCdcProjectAreRefused() throws Exception {
        project(pollingConfig(), false);
        var tool = new UpdateProjectScheduleTool(projects, actions);
        ObjectNode args = projectArgs(7);
        args.put("cronExpression", "every five minutes");
        assertThatThrownBy(() -> tool.execute(args, ctx))
                .hasMessage("error.project.cron.invalid");

        SyncConfig binlog = pollingConfig();
        binlog.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        project(binlog, false);
        args.put("cronExpression", "0 0/5 * * * ?");
        assertThatThrownBy(() -> tool.execute(args, ctx))
                .hasMessage("error.project.schedule.not.polling");
    }

    // --- connection -----------------------------------------------------------------------

    private DatabaseConfig connection(String extraParams, Project... using) {
        DatabaseConfig connection = new DatabaseConfig();
        connection.setId(3L);
        connection.setName("prod-mysql");
        connection.setOwnerUsername("ops1");
        connection.setType(com.qqmu.jync.model.DatabaseType.MYSQL);
        connection.setExtraParams(extraParams);
        when(databases.findById(3L)).thenReturn(Optional.of(connection));
        when(databases.projectsUsing(3L)).thenReturn(List.of(using));
        return connection;
    }

    private Project otherProject(String name, boolean enabled) {
        Project project = new Project();
        project.setId(11L);
        project.setName(name);
        project.setOwnerUsername("ops1");
        project.setEnabled(enabled);
        return project;
    }

    @Test
    void connectionDetailsExposeParametersAndDependentsButNoIdentityFields() {
        connection("useSSL=false&connectTimeout=3000&password=hunter2",
                otherProject("orders", true));
        var tool = new ConnectionDetailsTool(databases);

        ObjectNode args = mapper.createObjectNode();
        args.put("connectionId", 3);
        String json = mapper.writeValueAsString(tool.execute(args, ctx));

        assertThat(json).contains("\"name\":\"prod-mysql\"")
                .contains("\"connectTimeout\":\"3000\"")
                .contains("\"projectsUsing\":[\"orders\"]")
                .contains("\"runningProjects\":[\"orders\"]")
                .doesNotContain("hunter2")
                .contains("\"password\":\"<redacted>\"")
                .doesNotContain("jdbc:");
    }

    @Test
    void aDriverParameterCardShowsSetAndRemovedValuesAndStoresOnlyTheDelta() throws Exception {
        connection("useSSL=false&connectTimeout=3000");
        var tool = new UpdateConnectionParamsTool(databases, actions);
        ObjectNode args = mapper.createObjectNode();
        args.put("connectionId", 3);
        args.putObject("set").put("connectTimeout", "5000").put("tcpKeepAlive", "true");
        args.putArray("remove").add("useSSL");

        String json = mapper.writeValueAsString(tool.execute(args, ctx));

        assertThat(json).contains("\"titleKey\":\"support.action.connection.update_params\"")
                .contains("\"labelKey\":\"connectTimeout\"")
                .contains("\"before\":\"3000\"")
                .contains("\"after\":\"5000\"")
                .contains("\"labelKey\":\"useSSL\"")
                .contains("\"afterIsDefault\":true")
                .contains("\"affectedProjects\":[]");
        var result = (Map<?, ?>) mapper.readValue(json, Map.class);
        var pending = actions.take((String) result.get("token"), "ops1").orElseThrow();
        assertThat(pending.kind()).isEqualTo(SupportActionStore.Kind.CONNECTION);
        @SuppressWarnings("unchecked")
        Map<String, Object> set = (Map<String, Object>) pending.payload().get("set");
        assertThat(set).containsEntry("connectTimeout", "5000")
                .containsEntry("tcpKeepAlive", "true");
        @SuppressWarnings("unchecked")
        List<Object> remove = (List<Object>) pending.payload().get("remove");
        assertThat(remove).containsExactly("useSSL");
    }

    @Test
    void aConnectionSharedWithARunningTaskIsRefusedBeforeACardExists() {
        connection("useSSL=false", otherProject("orders", true));
        when(databases.isRunning(any())).thenReturn(true);
        var tool = new UpdateConnectionParamsTool(databases, actions);
        ObjectNode args = mapper.createObjectNode();
        args.put("connectionId", 3);
        args.putObject("set").put("connectTimeout", "5000");

        String json = mapper.writeValueAsString(tool.execute(args, ctx));
        assertThat(json).contains("error.connection.edit.running")
                .contains("orders")
                .doesNotContain("\"token\"");
    }

    @Test
    void anOperatorCannotChangeAParameterUnderAnotherAccountTask() {
        connection("useSSL=false", otherProject("alice-sync", false));
        databases.projectsUsing(3L).get(0).setOwnerUsername("alice");
        var tool = new UpdateConnectionParamsTool(databases, actions);
        ObjectNode args = mapper.createObjectNode();
        args.put("connectionId", 3);
        args.putObject("set").put("connectTimeout", "5000");

        assertThat(mapper.writeValueAsString(tool.execute(args, ctx)))
                .contains("error.connection.sharedByOthers")
                .contains("alice-sync")
                .doesNotContain("\"token\"");
    }

    @Test
    void unsafeDriverParametersAreRefusedAndAnOwnershipMismatchIsDenied() {
        connection("useSSL=false");
        var tool = new UpdateConnectionParamsTool(databases, actions);
        ObjectNode unsafe = mapper.createObjectNode();
        unsafe.put("connectionId", 3);
        unsafe.putObject("set").put("autoDeserialize", "true");
        assertThatThrownBy(() -> tool.execute(unsafe, ctx))
                .hasMessage("error.connection.url.unsafe");

        ObjectNode credential = mapper.createObjectNode();
        credential.put("connectionId", 3);
        credential.putObject("set").put("password", "hunter2");
        assertThatThrownBy(() -> tool.execute(credential, ctx))
                .hasMessage("error.support.param.notAllowed");

        DatabaseConfig foreign = connection("useSSL=false");
        foreign.setOwnerUsername("alice");
        ObjectNode args = mapper.createObjectNode();
        args.put("connectionId", 3);
        args.putObject("set").put("connectTimeout", "5000");
        assertThat(mapper.writeValueAsString(tool.execute(args, ctx)))
                .contains("error.accessDenied");
    }

    @Test
    void anUnchangedDriverParameterSetIsNotPresentedAsACard() {
        connection("connectTimeout=3000");
        var tool = new UpdateConnectionParamsTool(databases, actions);
        ObjectNode args = mapper.createObjectNode();
        args.put("connectionId", 3);
        args.putObject("set").put("connectTimeout", "3000");

        assertThat(mapper.writeValueAsString(tool.execute(args, ctx)))
                .contains("error.support.param.unchanged");
    }

    @Test
    void adminMayEditAnotherOwnersConnection() {
        connection("useSSL=false");
        DatabaseConfig foreign = databases.findById(3L).orElseThrow();
        foreign.setOwnerUsername("alice");
        var tool = new UpdateConnectionParamsTool(databases, actions);
        ToolContext adminCtx = new ToolContext(AccessGuard.of("admin", UserRole.ADMIN));
        ObjectNode args = mapper.createObjectNode();
        args.put("connectionId", 3);
        args.putObject("set").put("connectTimeout", "5000");

        assertThat(mapper.writeValueAsString(tool.execute(args, adminCtx)))
                .contains("\"ok\":true");
    }

    @Test
    void moreThanTwentyConnectionChangesAreRefused() {
        connection("useSSL=false");
        var tool = new UpdateConnectionParamsTool(databases, actions);
        ObjectNode args = mapper.createObjectNode();
        args.put("connectionId", 3);
        var set = args.putObject("set");
        for (int i = 0; i < 21; i++) {
            set.put("p" + i, "v");
        }

        assertThat(mapper.writeValueAsString(tool.execute(args, ctx)))
                .contains("error.support.param.tooMany")
                .doesNotContain("\"token\"");
    }

    @Test
    void anOversizedConnectionCardIsRefusedInsteadOfBeingTruncatedMidJson() {
        connection("useSSL=false");
        var tool = new UpdateConnectionParamsTool(databases, actions);
        ObjectNode args = mapper.createObjectNode();
        args.put("connectionId", 3);
        var set = args.putObject("set");
        for (int i = 0; i < 20; i++) {
            set.put("param" + i, "v".repeat(500));
        }

        assertThat(mapper.writeValueAsString(tool.execute(args, ctx)))
                .contains("error.support.param.tooMany")
                .doesNotContain("\"token\"");
    }

    @Test
    void aNonIntegerOrEmptyChangeSetIsRejected() {
        project(pollingConfig(), false);
        var tool = new UpdateProjectParamsTool(projects, actions);
        ObjectNode empty = projectArgs(7);
        empty.putObject("changes");
        assertThatThrownBy(() -> tool.execute(empty, ctx))
                .hasMessage(SupportArgs.BAD_ARGS);
    }
}
