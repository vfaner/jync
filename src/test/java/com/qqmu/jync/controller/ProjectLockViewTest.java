package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * Lock visibility on the project detail page.
 *
 * <p>A held lease explains why start/sync-now/delete refuse to run, so the page must name the
 * holder and its lease expiry; an expired lease must be visibly distinct (the holder is gone,
 * the row just has not been reclaimed). The force-unlock escape hatch is admin-only and only
 * offered while a lease is actually held.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectLockViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;

    private static RequestPostProcessor as(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role == UserRole.ADMIN ? "admin" : "view");
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    /** Stubs the detail route with a task row whose lock fields the caller controls. */
    private void stubDetail(SyncTask task) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(true);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.ofNullable(task));
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
    }

    private static SyncTask lockedTask(Instant expiry) {
        SyncTask task = new SyncTask();
        task.setProjectId(1L);
        task.setLockOwner("node-A");
        task.setLockExpiresAt(expiry);
        return task;
    }

    private String render(UserRole role) throws Exception {
        return mvc.perform(get("/projects/1").with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void adminSeesHolderLeaseAndTheForceUnlockButton() throws Exception {
        stubDetail(lockedTask(Instant.now().plus(5, ChronoUnit.MINUTES)));

        String page = render(UserRole.ADMIN);

        assertThat(page).contains("node-A");
        assertThat(page).contains("/api/projects/1/force-unlock");
        // Held, not expired: the warning badge and its resolved labels.
        assertThat(page).contains("badge-warn");
        assertThat(page).doesNotContain("??project.lock", "??msg.lock.forced");
    }

    @Test
    void anExpiredLeaseIsFlaggedAsDanger() throws Exception {
        stubDetail(lockedTask(Instant.now().minus(5, ChronoUnit.MINUTES)));

        String page = render(UserRole.ADMIN);

        assertThat(page).contains("node-A");
        assertThat(page).contains("badge-danger");
        assertThat(page).doesNotContain("??project.lock.expired");
    }

    @Test
    void viewerSeesTheLockButGetsNoForceUnlockButton() throws Exception {
        stubDetail(lockedTask(Instant.now().plus(5, ChronoUnit.MINUTES)));

        String page = render(UserRole.VIEWER);

        // The lock row is read-only information a viewer needs to understand refusals...
        assertThat(page).contains("node-A");
        // ...but the override itself is an admin mutation and must not be reachable.
        assertThat(page).doesNotContain("force-unlock");
    }

    @Test
    void noLockRowOrButtonWhenTheLeaseIsFree() throws Exception {
        stubDetail(new SyncTask());

        String page = render(UserRole.ADMIN);

        assertThat(page).doesNotContain("force-unlock");
        assertThat(page).doesNotContain("??project.lock");
    }
}
