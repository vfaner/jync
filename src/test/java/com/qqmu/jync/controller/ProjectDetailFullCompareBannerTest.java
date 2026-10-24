package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * The yellow FULL_COMPARE load warning on the task detail page.
 *
 * <p>Two cases share one banner: a table over the automatic row cap (an explicit strategy
 * choice bypasses it), and a table that is within the cap but too heavy for the task's current
 * poll interval. Both must name the table, its size, and the interval worth using.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectDetailFullCompareBannerTest {

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

    private void stubDetail(ProjectService.FullCompareLoadReport report) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.FULL_COMPARE);
        when(projectService.loadConfig(project)).thenReturn(config);
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.findFullLoadProgress(1L)).thenReturn(java.util.Map.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
        when(projectService.inspectFullCompareLoad(eq(1L), any())).thenReturn(report);
    }

    private static TableMeta table(String name, long rows) {
        TableMeta table = new TableMeta();
        table.setName(name);
        table.setApproximateRowCount(rows);
        table.setPrimaryKeys(List.of("ID"));
        return table;
    }

    private static ProjectService.FullCompareLoad load(
            String name, long rows, boolean overCap, long recommendedMs) {
        return new ProjectService.FullCompareLoad(table(name, rows), overCap, recommendedMs);
    }

    private static ProjectService.FullCompareLoadReport report(
            ProjectService.FullCompareLoad... loads) {
        return new ProjectService.FullCompareLoadReport(2_000L, 20_000L, List.of(loads));
    }

    private String render() throws Exception {
        return mvc.perform(get("/projects/1").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aTableOverTheCapNamesTheLimitAndTheSuggestedInterval() throws Exception {
        stubDetail(report(load("BIG_TABLE", 50_000L, true, 100_000L)));

        String html = render();

        assertThat(html).contains("Full-compare load warning");
        assertThat(html).contains("current poll interval is 2,000 ms");
        assertThat(html).contains("BIG_TABLE");
        assertThat(html).contains("~50,000 rows");
        assertThat(html).contains("20,000-row limit");
        assertThat(html).contains("at least 100 s");
        assertThat(html).contains("Incremental polling");
    }

    @Test
    void aTableWithinTheCapButHeavyForTheIntervalIsAlsoShown() throws Exception {
        stubDetail(report(load("FIFTEEN_K", 15_000L, false, 30_000L)));

        String html = render();

        assertThat(html).contains("Full-compare load warning");
        assertThat(html).contains("FIFTEEN_K");
        assertThat(html).contains("~15,000 rows");
        assertThat(html).contains("at least 30 s");
        assertThat(html).doesNotContain("20,000-row limit");
    }

    @Test
    void anOverCapTableOnAnAlreadySlowIntervalDoesNotSuggestAShorterOne() throws Exception {
        // The cap is the reason here; 60 s is already more than the ~42 s the load estimate
        // would ask for, so the interval suggestion would be noise pointing the wrong way.
        stubDetail(new ProjectService.FullCompareLoadReport(60_000L, 20_000L,
                List.of(load("BIG_TABLE", 20_001L, true, 42_000L))));

        String html = render();

        assertThat(html).contains("BIG_TABLE");
        assertThat(html).contains("20,000-row limit");
        assertThat(html).doesNotContain("at least 42 s");
    }

    @Test
    void aProjectWithNoWarningShowsNoBanner() throws Exception {
        stubDetail(report());

        String html = render();

        assertThat(html).doesNotContain("Full-compare load warning");
    }
}
