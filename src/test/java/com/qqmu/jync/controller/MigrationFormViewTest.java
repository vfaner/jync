package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.MigrationRunRepository;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.migration.MigrationConnectionService;
import com.qqmu.jync.service.migration.MigrationPrecheckResult;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Check;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Level;
import com.qqmu.jync.service.migration.MigrationTaskService;
import com.qqmu.jync.service.migration.MigrationTypeHandler;
import com.qqmu.jync.service.migration.MigrationTypeRegistry;

@WebMvcTest(MigrationController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class MigrationFormViewTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @MockBean private MigrationTaskService taskService;
    @MockBean private MigrationConnectionService connectionService;
    @MockBean private MigrationTypeRegistry registry;
    @MockBean private MigrationRunRepository runRepository;
    @MockBean private MigrationTypeHandler handler;

    @BeforeEach
    void setUp() {
        when(registry.isRegistered(MigrationType.ES_TO_EASYSEARCH)).thenReturn(true);
        when(registry.find(MigrationType.ES_TO_EASYSEARCH)).thenReturn(Optional.of(handler));
        when(handler.type()).thenReturn(MigrationType.ES_TO_EASYSEARCH);
        when(handler.sourceKinds()).thenReturn(Set.of(MigrationConnectionKind.ELASTICSEARCH,
                MigrationConnectionKind.OPENSEARCH));
        when(handler.targetKinds()).thenReturn(Set.of(MigrationConnectionKind.EASYSEARCH));
        when(handler.defaultOptions()).thenReturn(defaults());
        when(handler.parseOptions(any())).thenReturn(defaults());
        when(connectionService.findAll()).thenReturn(List.of(
                connection(1L, "es-source", MigrationConnectionKind.ELASTICSEARCH),
                connection(2L, "es-target", MigrationConnectionKind.EASYSEARCH)));
    }

    @Test
    void theNewFormShowsTheBasicFieldsAndReallyRendersTheEsOptions() throws Exception {
        String page = mvc.perform(get("/migrations/es-to-easysearch/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(view().name("migration-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("name=\"name\"")
                .contains("name=\"sourceConnectionId\"")
                .contains("name=\"targetConnectionId\"")
                .contains("name=\"cronExpression\"")
                .contains("data-role=\"options-es\"")
                .contains("name=\"opt.indicesText\"")
                .contains("name=\"opt.writeMode\"")
                .contains("data-role=\"discover-indices\"");
    }

    @Test
    void theScheduleHelpTextMatchesWhatSavingActuallyDoes() throws Exception {
        String page = mvc.perform(get("/migrations/es-to-easysearch/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // save() applies the checkbox and schedules the task right after commit, so the old
        // "Saving does not enable the task" help text told users the opposite of the truth.
        assertThat(page).contains("Saving with this box checked enables the schedule immediately")
                .doesNotContain("Saving does not enable the task");
    }

    @Test
    void theEditFormPrefillsStoredOptionsAndScheduling() throws Exception {
        MigrationTask task = new MigrationTask();
        task.setId(9L);
        task.setName("nightly");
        task.setType(MigrationType.ES_TO_EASYSEARCH);
        task.setSourceConnectionId(1L);
        task.setTargetConnectionId(2L);
        task.setCronExpression("0 0 3 * * *");
        task.setEnabled(true);
        ObjectNode stored = defaults();
        stored.put("indicesText", "orders-*");
        stored.put("writeMode", "APPEND");
        task.setOptionsJson(stored.toString());
        when(taskService.findById(9L)).thenReturn(Optional.of(task));

        String page = mvc.perform(get("/migrations/es-to-easysearch/9/edit").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(view().name("migration-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("orders-*")
                .containsPattern("(?s)value=\"APPEND\"[^>]*selected")
                .contains("value=\"0 0 3 * * *\"");
    }

    @Test
    void everyPrecheckLevelIsRenderedWithItsDetail() throws Exception {
        when(connectionService.findById(1L)).thenReturn(Optional.of(
                connection(1L, "es-source", MigrationConnectionKind.ELASTICSEARCH)));
        when(connectionService.findById(2L)).thenReturn(Optional.of(
                connection(2L, "es-target", MigrationConnectionKind.EASYSEARCH)));
        when(handler.precheck(any(), any(), any(), any())).thenReturn(
                new MigrationPrecheckResult(List.of(
                        new Check(Level.OK, "migration.precheck.source.reachable", "es-source ok"),
                        new Check(Level.WARN, "migration.precheck.index.mapping",
                                "orders: knn_vector"),
                        new Check(Level.ERROR, "migration.precheck.compat.failed",
                                "target refused mapping"))));

        String page = mvc.perform(post("/migrations/es-to-easysearch/precheck")
                        .with(asAdmin()).with(csrf())
                        .param("name", "nightly")
                        .param("sourceConnectionId", "1")
                        .param("targetConnectionId", "2"))
                .andExpect(status().isOk())
                .andExpect(view().name("migration-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-role=\"precheck-result\"")
                .contains("precheck-ok").contains("precheck-warn").contains("precheck-error")
                .contains("es-source ok").contains("knn_vector")
                .contains("target refused mapping");
    }

    @Test
    void aRejectedSaveRendersTheSameFormWithTheErrorAndTheTypedOptions() throws Exception {
        when(taskService.save(any(), any()))
                .thenThrow(new IllegalArgumentException("error.migration.name.required"));

        String page = mvc.perform(post("/migrations/es-to-easysearch/save")
                        .with(asAdmin()).with(csrf())
                        .param("name", "")
                        .param("sourceConnectionId", "1")
                        .param("targetConnectionId", "2")
                        .param("opt.indicesText", "orders-*"))
                .andExpect(status().isOk())
                .andExpect(view().name("migration-form"))
                .andExpect(model().attribute("error", "error.migration.name.required"))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-role=\"options-es\"")
                .contains("orders-*");
    }

    private static ObjectNode defaults() {
        ObjectNode options = JSON.createObjectNode();
        options.put("indicesText", "");
        options.put("targetNaming", "SAME");
        options.put("targetPrefix", "");
        options.put("targetSuffix", "");
        options.put("writeMode", "OVERWRITE");
        options.put("copySettings", true);
        options.put("copyMappings", true);
        options.put("copyAliases", false);
        options.put("continueOnError", true);
        options.put("batchSize", 1000);
        options.put("maxBulkBytes", 10485760);
        options.put("scrollTtl", "5m");
        options.put("verifyCount", true);
        options.put("verifySampleSize", 100);
        options.put("probeCompatibility", true);
        options.put("maxIndices", 200);
        return options;
    }

    private static MigrationConnection connection(long id, String name,
                                                  MigrationConnectionKind kind) {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(id);
        connection.setName(name);
        connection.setKind(kind);
        connection.setBaseUrl("http://127.0.0.1:9200");
        return connection;
    }

    private static RequestPostProcessor asAdmin() {
        return authentication(principal(UserRole.ADMIN));
    }

    private static UsernamePasswordAuthenticationToken principal(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role.name().toLowerCase());
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails details = new SyncUserDetails(user, false);
        return new UsernamePasswordAuthenticationToken(
                details, null, details.getAuthorities());
    }
}
