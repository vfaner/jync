package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.ChangeLogService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * Who gets the skipped-DDL button on the change-log page.
 *
 * <p>The button posts to a handler that answers with JSON. Its only server-side gate is
 * "may this caller edit the log's project", and a denial there leaves the servlet chain as a
 * 302 to {@code /?denied} — the page's fetch then parses HTML as JSON. So the button has to
 * follow the same rule as the handler: an operator sees it only on its own projects, an
 * administrator on every row.
 */
@WebMvcTest(ChangeLogController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class})
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ChangeLogDdlOwnershipViewTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChangeLogService changeLogService;

    @MockitoBean
    private ProjectService projectService;

    private static RequestPostProcessor as(String username, UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(username);
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static Project project(long id, String owner) {
        Project project = new Project();
        project.setId(id);
        project.setName("p" + id);
        project.setOwnerUsername(owner);
        return project;
    }

    /** A skipped-DDL entry as the CDC writer stores it: details carry the statement. */
    private static ChangeLog skippedDdl(long id, long projectId) {
        ChangeLog entry = new ChangeLog();
        entry.setId(id);
        entry.setProjectId(projectId);
        entry.setObjectType(ObjectType.TABLE);
        entry.setObjectName("orders");
        entry.setChangeType(ChangeType.ALTER);
        entry.setSuccess(false);
        entry.setOccurredAt(Instant.now());
        entry.setDetails("CDC_SKIPPED_DDL:ALTER TABLE orders ADD COLUMN note" + id + " VARCHAR(20)");
        entry.setResolutionStatus("FAILED");
        entry.setDdlExecuted(false);
        entry.setReplayDone(false);
        return entry;
    }

    private void stubTwoProjectsWithOneOpenDdlEach() {
        Pageable pageable = PageRequest.of(0, 50);
        Page<ChangeLog> page = new PageImpl<>(
                List.of(skippedDdl(1L, 100L), skippedDdl(2L, 200L)), pageable, 2);
        when(changeLogService.count(any(), anyBoolean())).thenReturn(2L);
        when(changeLogService.page(any(), anyBoolean(), any(Pageable.class))).thenReturn(page);
        when(projectService.findAll())
                .thenReturn(List.of(project(100L, "alice"), project(200L, "ops")));
    }

    private String render(RequestPostProcessor who) throws Exception {
        return mvc.perform(get("/change-logs").header("Accept-Language", "zh-CN").with(who))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anOperatorGetsTheDdlButtonOnItsOwnProjectOnly() throws Exception {
        stubTwoProjectsWithOneOpenDdlEach();

        String html = render(as("ops", UserRole.OPS));

        // Both statements stay readable, as every resource is; only the action differs.
        assertThat(html).contains("note1").contains("note2");
        assertThat(count(html, "data-role=\"ddl-open\"")).isEqualTo(1);
        assertThat(html).contains("data-ddl-id=\"2\"");
        assertThat(html).doesNotContain("data-ddl-id=\"1\"");
    }

    @Test
    void anAdministratorGetsTheDdlButtonOnEveryRow() throws Exception {
        stubTwoProjectsWithOneOpenDdlEach();

        String html = render(as("admin", UserRole.ADMIN));

        assertThat(count(html, "data-role=\"ddl-open\"")).isEqualTo(2);
        assertThat(html).contains("data-ddl-id=\"1\"").contains("data-ddl-id=\"2\"");
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            found++;
            from += needle.length();
        }
        return found;
    }
}
