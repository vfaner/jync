package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * On a phone the project list used to squeeze five columns into 390px. The 详情 column lost
 * that fight: its result chip breaks at every character, so a one-line summary ("DDL=4, rows=
 * +58~0, tables=4, 336ms") rendered as a twelve-line strip of single letters while the row
 * grew to the height of the screen.
 *
 * <p>The table therefore carries its own class, and the stylesheet turns its rows into wrapping
 * rows below 640px: no header, the name on its own line, then status, last-sync time and the
 * result chip side by side. The test pins the two halves of that contract — the marker the
 * template renders and the rule the stylesheet must keep — because either alone is invisible
 * at desktop width, which is where this page is developed.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectListPhoneLayoutTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProjectService projectService;

    @MockBean
    private DatabaseConfigService databaseConfigService;

    @MockBean
    private CdcOperationsService cdcOperationsService;

    @MockBean
    private CdcConsumerManager cdcConsumerManager;

    @MockBean
    private CdcPositionStore cdcPositionStore;

    @Test
    void theProjectTableIsMarkedForThePhoneLayout() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setName("p1");
        when(projectService.count()).thenReturn(1L);
        when(projectService.findPage(any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(project));
        when(projectService.findTasks(any())).thenReturn(Map.of());
        when(projectService.loadConfig(any())).thenReturn(new SyncConfig());

        String html = mvc.perform(get("/projects").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("class=\"tbl tbl-projects\"");
    }

    @Test
    void theStylesheetStacksTheRowsBelow640px() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        String block = mediaBlock(css, "@media (max-width: 640px)", ".tbl-projects");

        assertThat(block)
                .as("the phone rule that unwraps a project row must survive")
                .isNotNull();
        assertThat(block).contains("display: flex").contains("flex-wrap: wrap");
        // The chip's global word-break: break-all is what produced the single-letter column;
        // inside this table the summary has to break between its "k=v" fields, not inside one.
        assertThat(css).contains(".tbl-projects td .chip");
    }

    /** The body of the media block that also mentions {@code marker}, or null when none does. */
    private static String mediaBlock(String css, String header, String marker) {
        int from = 0;
        while ((from = css.indexOf(header, from)) >= 0) {
            int open = css.indexOf('{', from);
            if (open < 0) {
                return null;
            }
            int depth = 0;
            int i = open;
            for (; i < css.length(); i++) {
                if (css.charAt(i) == '{') {
                    depth++;
                } else if (css.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            String body = css.substring(open, Math.min(i + 1, css.length()));
            if (body.contains(marker)) {
                return body;
            }
            from = i + 1;
        }
        return null;
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
