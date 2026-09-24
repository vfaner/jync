package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The project list used to render every project in one table. The pager fragment must slice
 * it server-side and render the shared bar — default twenty per page, the requested size
 * selected, numbered links carrying page and size through.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectPagerViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProjectService projectService;

    @MockBean
    private DatabaseConfigService databaseConfigService;

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

    private void stubProjects(int count) {
        List<Project> all = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Project project = new Project();
            project.setId((long) i);
            project.setName("p" + i);
            all.add(project);
        }
        when(projectService.count()).thenReturn((long) count);
        when(projectService.findAll()).thenReturn(all);
        when(projectService.findTask(any())).thenReturn(Optional.empty());
    }

    private String render(String query) throws Exception {
        return mvc.perform(get("/projects" + query).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Counts rendered project rows by the name link each one starts with. */
    private static int projectRows(String html) {
        int count = 0;
        int from = 0;
        while ((from = html.indexOf("font-weight:600\" href=\"/projects/", from)) >= 0) {
            count++;
            from++;
        }
        return count;
    }

    @Test
    void theFirstPageHoldsTwentyRowsAndLinksToTheRest() throws Exception {
        stubProjects(45);

        String html = render("");

        assertThat(projectRows(html)).isEqualTo(20);
        assertThat(html)
                .contains("data-role=\"pager\"")
                .contains("is-current\">1</span>")
                // 45 projects at 20 per page: three numbered pages, no ellipsis.
                .contains("page=2&amp;size=20")
                .contains("page=3&amp;size=20");
    }

    @Test
    void pageAndSizeParamsSelectTheSliceAndTheDropdownEntry() throws Exception {
        stubProjects(45);

        String html = render("?page=3&size=15");

        assertThat(projectRows(html)).isEqualTo(15);
        assertThat(html)
                .contains("is-current\">3</span>")
                .contains("value=\"15\" selected=\"selected\"");
    }
}
