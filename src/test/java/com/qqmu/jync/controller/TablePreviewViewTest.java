package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AppUser;
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
 * The read-only preview entry points on the project detail page.
 *
 * <p>Every progress row must offer both sides, and the modal must be present in the page
 * (app.js only fills it). Labels resolve through i18n, never as raw keys.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class TablePreviewViewTest {

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

    private String render() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(true);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findFullLoadProgress(1L)).thenReturn(java.util.Map.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());

        SyncProgress progress = new SyncProgress();
        progress.setObjectName("ITEMS");
        when(projectService.findProgress(1L)).thenReturn(List.of(progress));

        return mvc.perform(get("/projects/1").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void eachProgressRowOffersBothSidesAndTheModalIsRendered() throws Exception {
        String html = render();

        assertThat(html).contains("data-role=\"preview\"");
        assertThat(html).contains("data-side=\"source\"");
        assertThat(html).contains("data-side=\"target\"");
        assertThat(html).contains("data-table=\"ITEMS\"");
        assertThat(html).contains("data-action=\"/api/projects/1/preview\"");
        assertThat(html).contains("id=\"preview-modal\"");
        assertThat(html).contains("id=\"preview-body\"");
    }

    @Test
    void thePreviewLabelsAreLocalizedInTheEnglishPage() throws Exception {
        String html = render();

        assertThat(html).contains(">Preview</th>");
        assertThat(html).contains(">Source</span>");
        assertThat(html).contains(">Target</span>");
        // The key name itself now legitimately appears in the JS dictionary; unresolved
        // messages still must not leak in the "??key??" form.
        assertThat(html).doesNotContain("??preview");
    }

    /**
     * app.js renders the modal title and the side label through {@code t()}, which reads
     * {@code window.JyncMessages}. A key missing from that dictionary does not fail the page —
     * it silently shows the raw key ("preview.title · customers") to the user.
     */
    @Test
    void thePreviewModalTitleAndSideLabelsAreInTheJsDictionary() throws Exception {
        String html = render();

        assertThat(html).contains("'preview.title': \"Preview\"");
        assertThat(html).contains("'preview.source': \"Source\"");
        assertThat(html).contains("'preview.target': \"Target\"");
    }
}
