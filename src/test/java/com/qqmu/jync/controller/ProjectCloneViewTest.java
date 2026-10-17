package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * Cloning a project: one source feeding a second target.
 *
 * <p>Reuses the normal create form rather than a bespoke endpoint, so every existing rule
 * (name uniqueness, target required, role-filtered connection lists) still applies. The
 * source and its sync settings are prefilled server-side; the target is deliberately left
 * empty — writing the same tables to the same database is what cloning must not do.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectCloneViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private Project storedSource() {
        Project source = new Project();
        source.setId(1L);
        source.setName("proj");
        source.setSourceDbId(10L);
        source.setTargetDbId(20L);
        when(projectService.findById(1L)).thenReturn(Optional.of(source));
        when(databaseConfigService.findByRole(any())).thenReturn(List.of());
        when(databaseConfigService.findById(any())).thenReturn(Optional.empty());
        return source;
    }

    @Test
    void theCreateFormCopiesTheSourceAndSettingsAndLeavesTheTargetEmpty() throws Exception {
        storedSource();

        MvcResult result = mvc.perform(get("/projects/new").param("from", "1").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn();

        Project form = (Project) result.getModelAndView().getModel().get("project");
        assertThat(form.getSourceDbId()).isEqualTo(10L);
        assertThat(form.getTargetDbId()).isNull();
        assertThat(form.getName()).isEqualTo("proj (copy)");
        assertThat(result.getModelAndView().getModel().get("copyFrom")).isEqualTo(1L);

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("name=\"copyFrom\"").contains("value=\"1\"");
        // The hint explains why the target selector starts blank.
        assertThat(html).contains("Choose a different target");
    }

    @Test
    void anUnknownSourceFallsBackToAPlainCreateForm() throws Exception {
        when(projectService.findById(9L)).thenReturn(Optional.empty());
        when(databaseConfigService.findByRole(any())).thenReturn(List.of());

        MvcResult result = mvc.perform(get("/projects/new").param("from", "9").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn();

        Project form = (Project) result.getModelAndView().getModel().get("project");
        assertThat(form.getName()).isNull();
        assertThat(form.getSourceDbId()).isNull();
        assertThat(result.getModelAndView().getModel().get("copyFrom")).isNull();
    }

    @Test
    void savingTheCloneCopiesTheSourceSettingsOntoTheNewProject() throws Exception {
        Project saved = new Project();
        saved.setId(2L);
        when(projectService.save(any())).thenReturn(saved);

        mvc.perform(post("/projects/save").with(asAdmin()).with(csrf())
                        .param("name", "proj (copy)")
                        .param("sourceDbId", "10")
                        .param("targetDbId", "30")
                        .param("copyFrom", "1"))
                .andExpect(status().is3xxRedirection());

        // After the row exists, copy the stored sync settings verbatim. Runtime state is
        // not copied because a brand-new project has none — and must start from zero.
        verify(projectService).copyConfig(1L, 2L);
    }

    @Test
    void theDetailPageOffersTheCloneEntryPointToAdmins() throws Exception {
        Project project = storedSource();
        when(projectService.loadConfig(project)).thenReturn(new com.qqmu.jync.dto.SyncConfig());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.findFullLoadProgress(1L)).thenReturn(java.util.Map.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());

        String page = mvc.perform(get("/projects/1").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("href=\"/projects/new?from=1\"");
        assertThat(page).contains(">Clone</span>");
    }
}
