package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * The project page names the views and routines whose {@code ||} will mean logical OR on the
 * target.
 *
 * <p>The structure sync cannot fail on this: GoldenDB and the other MySQL-family targets
 * accept the statement and create the object. It only returns the wrong rows afterwards.
 * The warning therefore has to reach the operator before the first run, and it has to name
 * the objects — a page-wide hint would send them hunting through the conversion list.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectDetailStringConcatViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    @Test
    void theAffectedObjectsAreNamedOnThePage() throws Exception {
        stubProject();
        when(projectService.findStringConcatenationWarnings(1L)).thenReturn(List.of(
                new ProjectService.StringConcatWarning(ObjectType.VIEW, "ORDERS_V"),
                new ProjectService.StringConcatWarning(ObjectType.PROCEDURE, "GREET")));

        String page = render("zh-CN");

        assertThat(page).contains("目标库把“||”当作逻辑或");
        assertThat(page).contains("ORDERS_V");
        assertThat(page).contains("GREET");
        // The remedy is the conversion review page, keyed to this project.
        assertThat(page).contains("/projects/1/conversions");
    }

    @Test
    void aCleanSchemaShowsNoBanner() throws Exception {
        stubProject();
        when(projectService.findStringConcatenationWarnings(1L)).thenReturn(List.of());

        assertThat(render("zh-CN")).doesNotContain("目标库把“||”当作逻辑或");
    }

    @Test
    void aFailedInspectionStillRendersThePage() throws Exception {
        stubProject();
        when(projectService.findStringConcatenationWarnings(1L))
                .thenThrow(new IllegalStateException("error.connection.failed"));

        // Advisory: an unreachable source is already the page's own warning, not a 500.
        assertThat(render("zh-CN")).doesNotContain("目标库把“||”当作逻辑或");
    }

    @Test
    void theEnglishPageExplainsTheHazard() throws Exception {
        stubProject();
        when(projectService.findStringConcatenationWarnings(1L)).thenReturn(List.of(
                new ProjectService.StringConcatWarning(ObjectType.VIEW, "ORDERS_V")));

        String page = render("en-US");

        assertThat(page).contains("The target reads “||” as logical OR");
        assertThat(page).doesNotContain("目标库把“||”当作逻辑或");
    }

    private void stubProject() {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of(cursor("EVENTS")));
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
        when(projectService.findUnindexedCursorTables(eq(1L), anyList())).thenReturn(Set.of());
    }

    private static SyncProgress cursor(String table) {
        SyncProgress p = new SyncProgress();
        p.setProjectId(1L);
        p.setObjectType(ObjectType.TABLE);
        p.setObjectName(table);
        p.setCursorColumn("UPDATED");
        p.setCursorStrategy("TIMESTAMP");
        return p;
    }

    private String render(String acceptLanguage) throws Exception {
        return mvc.perform(get("/projects/1")
                        .header("Accept-Language", acceptLanguage)
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

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
}
