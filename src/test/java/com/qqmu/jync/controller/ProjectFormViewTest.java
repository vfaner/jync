package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The project form's source selector must only offer SOURCE connections and its target selector
 * only TARGET connections — that separation is the whole point of classifying connections.
 * A currently selected connection whose role no longer matches (legacy data after backfill) is
 * still appended so the form can render and re-save the existing value.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectFormViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("x");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private DatabaseConfig config(long id, String name, ConnectionRole role) {
        DatabaseConfig c = new DatabaseConfig();
        c.setId(id);
        c.setName(name);
        c.setType(DatabaseType.MYSQL);
        c.setRole(role);
        return c;
    }

    private static String selectBlock(String html, String selectId) {
        Matcher m = Pattern.compile("(?s)<select[^>]*id=\"" + selectId + "\".*?</select>").matcher(html);
        assertThat(m.find()).as("select#%s must be rendered", selectId).isTrue();
        return m.group();
    }

    @Test
    void selectorsAreFilteredByRole() throws Exception {
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE))
                .thenReturn(List.of(config(1L, "src-only", ConnectionRole.SOURCE)));
        when(databaseConfigService.findByRole(ConnectionRole.TARGET))
                .thenReturn(List.of(config(2L, "tgt-only", ConnectionRole.TARGET)));

        String page = mvc.perform(get("/projects/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String sourceSelect = selectBlock(page, "sourceDbId");
        assertThat(sourceSelect).contains("src-only").doesNotContain("tgt-only");
        String targetSelect = selectBlock(page, "targetDbId");
        assertThat(targetSelect).contains("tgt-only").doesNotContain("src-only");
        assertThat(page).doesNotContain("??project.", "??db.");
    }

    @Test
    void editFormKeepsSelectedConnectionEvenWhenItsRoleNoLongerMatches() throws Exception {
        Project project = new Project();
        project.setId(5L);
        project.setName("legacy-project");
        project.setSourceDbId(99L);
        project.setTargetDbId(2L);
        when(projectService.findById(5L)).thenReturn(Optional.of(project));
        // 99 is classified TARGET now, so it is absent from the role-filtered source list...
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE)).thenReturn(List.of());
        when(databaseConfigService.findByRole(ConnectionRole.TARGET))
                .thenReturn(List.of(config(2L, "tgt-only", ConnectionRole.TARGET),
                        config(99L, "misfiled", ConnectionRole.TARGET)));
        // ...but must still be appended so the selector shows the real current value.
        when(databaseConfigService.findById(99L))
                .thenReturn(Optional.of(config(99L, "misfiled", ConnectionRole.TARGET)));

        String page = mvc.perform(get("/projects/5/edit").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String sourceSelect = selectBlock(page, "sourceDbId");
        assertThat(sourceSelect).contains("misfiled");
        assertThat(sourceSelect).containsPattern("value=\"99\"[^>]*selected");
    }
}
