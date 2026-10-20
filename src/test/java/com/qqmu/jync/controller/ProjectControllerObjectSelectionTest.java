package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * "Uncheck everything" is a legitimate choice: it must mean "sync none of this kind" and
 * save, not be rejected and not silently flip back to "sync all". The form distinguishes the
 * two readings of an empty payload with a hidden flag — without the flag an empty submission
 * is a listing failure or an old client, and the stored selection must survive untouched.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class ProjectControllerObjectSelectionTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    private SyncConfig storedConfig;

    /** Stubs the detail route; returns the config instance the controller will mutate. */
    private SyncConfig stubDetail() {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        storedConfig = new SyncConfig();
        storedConfig.setCaptureStrategy(CaptureStrategy.POLLING);
        when(projectService.loadConfig(project)).thenReturn(storedConfig);
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.findFullLoadProgress(1L)).thenReturn(java.util.Map.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
        return storedConfig;
    }

    @Test
    void nothingCheckedWithTheExplicitFlagSavesAndMeansSyncNone() throws Exception {
        stubDetail();

        MvcResult result = mvc.perform(post("/projects/1/config").with(csrf())
                        .param("objectSelectionExplicit", "true")
                        .param("syncStructure", "true")
                        .param("syncData", "true")
                        .param("syncIndexes", "true")
                        .param("syncViews", "true")
                        .param("syncProcedures", "true"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getFlashMap().get("error")).isNull();
        ArgumentCaptor<SyncConfig> captor = ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService).saveConfig(eq(1L), captor.capture(), isNull());
        SyncConfig saved = captor.getValue();
        assertThat(saved.isObjectSelectionExplicit()).isTrue();
        assertThat(saved.getTables()).isEmpty();
        assertThat(saved.getViews()).isEmpty();
        assertThat(saved.getProcedures()).isEmpty();
        assertThat(saved.includesTable("orders")).isFalse();
    }

    @Test
    void anEmptyPayloadWithoutTheFlagKeepsTheStoredSelection() throws Exception {
        SyncConfig stored = stubDetail();
        stored.setTables(new LinkedHashSet<>(List.of("orders")));
        stored.setViews(new LinkedHashSet<>(List.of("v_orders")));
        stored.setProcedures(new LinkedHashSet<>(List.of("p_nightly")));

        // The source listing failed (or an old client posted): no picker was rendered, so the
        // empty lists are absence of data, not a user choice. Wiping the selection here would
        // silently turn the project into "sync nothing".
        mvc.perform(post("/projects/1/config").with(csrf())
                        .param("syncStructure", "true")
                        .param("syncData", "true"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<SyncConfig> captor = ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService).saveConfig(eq(1L), captor.capture(), isNull());
        assertThat(captor.getValue().getTables()).containsExactly("orders");
        assertThat(captor.getValue().getViews()).containsExactly("v_orders");
        assertThat(captor.getValue().getProcedures()).containsExactly("p_nightly");
        assertThat(captor.getValue().isObjectSelectionExplicit()).isFalse();
    }

    @Test
    void detailRendersExplicitEmptySelectionUnchecked() throws Exception {
        SyncConfig stored = stubDetail();
        stored.setObjectSelectionExplicit(true);
        ProjectService.SourceObjects objects = mock(ProjectService.SourceObjects.class);
        when(objects.getTables()).thenReturn(List.of("orders"));
        when(objects.getViews()).thenReturn(List.of("v_orders"));
        when(objects.getProcedures()).thenReturn(List.of("p_nightly"));
        when(projectService.listSourceObjects(1L)).thenReturn(objects);

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(inputTag(page, "orders")).doesNotContain("checked");
        assertThat(inputTag(page, "v_orders")).doesNotContain("checked");
        assertThat(inputTag(page, "p_nightly")).doesNotContain("checked");
        // The flag travels with the next save, so the choice survives round trips.
        assertThat(page).contains("name=\"objectSelectionExplicit\"");
    }

    @Test
    void detailDisablesStartAndSyncNowWhenNothingIsSelected() throws Exception {
        SyncConfig stored = stubDetail();
        stored.setObjectSelectionExplicit(true);

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Starting would only schedule empty cycles; the page must not offer the action.
        assertThat(buttonTag(page, "/api/projects/1/start")).contains("disabled");
        assertThat(buttonTag(page, "/api/projects/1/sync-now")).contains("disabled");
        // And the page says why, since a disabled button cannot explain itself.
        assertThat(page).contains("No sync objects are selected");
    }

    @Test
    void detailKeepsStartEnabledForALegacyEmptySelection() throws Exception {
        stubDetail(); // explicit flag false: empty still means "everything"

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(buttonTag(page, "/api/projects/1/start")).doesNotContain("disabled");
        assertThat(buttonTag(page, "/api/projects/1/sync-now")).doesNotContain("disabled");
    }

    @Test
    void projectListDisablesStartOnlyForTheEmptySelectionProject() throws Exception {
        Project empty = new Project();
        empty.setId(1L);
        empty.setName("empty");
        Project legacy = new Project();
        legacy.setId(2L);
        legacy.setName("legacy");
        when(projectService.count()).thenReturn(2L);
        when(projectService.findPage(0, 20)).thenReturn(List.of(empty, legacy));
        when(projectService.findTasks(List.of(1L, 2L))).thenReturn(java.util.Map.of());
        SyncConfig emptyConfig = new SyncConfig();
        emptyConfig.setObjectSelectionExplicit(true);
        when(projectService.loadConfig(empty)).thenReturn(emptyConfig);
        when(projectService.loadConfig(legacy)).thenReturn(new SyncConfig());

        String page = mvc.perform(get("/projects"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(buttonTag(page, "/api/projects/1/start")).contains("disabled");
        assertThat(buttonTag(page, "/api/projects/2/start")).doesNotContain("disabled");
    }

    @Test
    void legacyEmptySelectionRendersEverythingChecked() throws Exception {
        stubDetail();
        ProjectService.SourceObjects objects = mock(ProjectService.SourceObjects.class);
        when(objects.getTables()).thenReturn(List.of("orders"));
        when(projectService.listSourceObjects(1L)).thenReturn(objects);

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(inputTag(page, "orders")).contains("checked");
    }

    @Test
    void detailOmitsTheFlagWhenTheSourceListingFailed() throws Exception {
        stubDetail();
        when(projectService.listSourceObjects(1L))
                .thenThrow(new IllegalStateException("error.source.objects.read.failed"));

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // No pickers were rendered, so this submission cannot carry a selection. Without the
        // flag the controller keeps the stored sets instead of wiping them.
        assertThat(page).doesNotContain("name=\"objectSelectionExplicit\"");
    }

    /** The <button> tag whose data-action points at the given URL. */
    private static String buttonTag(String page, String action) {
        int at = page.indexOf(action);
        assertThat(at).as("button for " + action).isPositive();
        int start = page.lastIndexOf("<button", at);
        int end = page.indexOf(">", at);
        return page.substring(start, end + 1);
    }

    /** The <input> tag whose value attribute names the given object. */
    private static String inputTag(String page, String name) {
        int at = page.indexOf("value=\"" + name + "\"");
        assertThat(at).as("input for " + name).isPositive();
        int start = page.lastIndexOf("<input", at);
        int end = page.indexOf(">", at);
        return page.substring(start, end + 1);
    }
}
