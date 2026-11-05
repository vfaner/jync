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
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisTargetService;

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
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;
    @MockBean private RedisTargetService redisTargetService;

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
        return config(id, name, role, DatabaseType.MYSQL);
    }

    private DatabaseConfig config(long id, String name, ConnectionRole role, DatabaseType type) {
        DatabaseConfig c = new DatabaseConfig();
        c.setId(id);
        c.setName(name);
        c.setType(type);
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
    void sourceSelectorOffersPollingEnginesButTargetSelectorDoesNot() throws Exception {
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE)).thenReturn(List.of(
                config(1L, "ch-src", ConnectionRole.SOURCE, DatabaseType.CLICKHOUSE),
                config(2L, "h2-src", ConnectionRole.SOURCE, DatabaseType.H2)));
        when(databaseConfigService.findByRole(ConnectionRole.TARGET)).thenReturn(List.of(
                config(3L, "mysql-tgt", ConnectionRole.TARGET),
                config(4L, "ch-tgt", ConnectionRole.TARGET, DatabaseType.CLICKHOUSE)));

        String page = mvc.perform(get("/projects/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 分析型引擎有只读方言，可以当源；H2 没有家族，仍然不出现。
        assertThat(selectBlock(page, "sourceDbId"))
                .contains("ch-src").doesNotContain("h2-src");
        // 同一个引擎不能当目标。
        assertThat(selectBlock(page, "targetDbId"))
                .contains("mysql-tgt").doesNotContain("ch-tgt");
    }

    @Test
    void theFormExplainsThatAnalyticEnginesAreSourceOnly() throws Exception {
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE))
                .thenReturn(List.of(config(1L, "ch-src", ConnectionRole.SOURCE,
                        DatabaseType.CLICKHOUSE)));
        when(databaseConfigService.findByRole(ConnectionRole.TARGET))
                .thenReturn(List.of(config(2L, "mysql-tgt", ConnectionRole.TARGET)));

        String page = mvc.perform(get("/projects/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The hint names the engines in both bundles, so asserting on the element and one
        // product name works whichever locale the test JVM resolves.
        assertThat(page).contains("id=\"source-only-hint\"").contains("ClickHouse");
        assertThat(page).doesNotContain("??project.");
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

    @Test
    void aRunningProjectShowsTheEndpointLockHint() throws Exception {
        Project project = new Project();
        project.setId(8L);
        project.setName("orders");
        project.setSourceDbId(1L);
        project.setTargetDbId(2L);
        when(projectService.findById(8L)).thenReturn(Optional.of(project));
        when(projectService.isRunning(project)).thenReturn(true);
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE))
                .thenReturn(List.of(config(1L, "src", ConnectionRole.SOURCE)));
        when(databaseConfigService.findByRole(ConnectionRole.TARGET))
                .thenReturn(List.of(config(2L, "tgt", ConnectionRole.TARGET)));

        String page = mvc.perform(get("/projects/8/edit").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The backend refuses the change anyway; the hint keeps the user from filling in
        // a whole edit (and losing a real save) before the error appears.
        assertThat(page).contains("id=\"endpoint-lock-hint\"");
    }

    @Test
    void aStoppedProjectShowsNoEndpointLockHint() throws Exception {
        Project project = new Project();
        project.setId(8L);
        project.setName("orders");
        project.setSourceDbId(1L);
        project.setTargetDbId(2L);
        when(projectService.findById(8L)).thenReturn(Optional.of(project));
        when(projectService.isRunning(project)).thenReturn(false);
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE))
                .thenReturn(List.of(config(1L, "src", ConnectionRole.SOURCE)));
        when(databaseConfigService.findByRole(ConnectionRole.TARGET))
                .thenReturn(List.of(config(2L, "tgt", ConnectionRole.TARGET)));

        String page = mvc.perform(get("/projects/8/edit").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain("endpoint-lock-hint");
    }

    @Test
    void aNewProjectOffersRedisAsATargetKindButDefaultsToADatabase() throws Exception {
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE))
                .thenReturn(List.of(config(1L, "src", ConnectionRole.SOURCE)));
        when(databaseConfigService.findByRole(ConnectionRole.TARGET)).thenReturn(List.of());
        when(redisTargetService.findAll()).thenReturn(List.of(redisTarget(5L, "cache-1")));

        String page = mvc.perform(get("/projects/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("name=\"targetKind\"");
        assertThat(page).contains("value=\"JDBC\"");
        assertThat(page).contains("value=\"REDIS\"");
        // Both target fields are rendered; the script swaps which one is active/required.
        assertThat(selectBlock(page, "targetDbId")).contains("required");
        assertThat(selectBlock(page, "targetRedisId")).contains("cache-1");
        assertThat(selectBlock(page, "targetRedisId")).doesNotContain("required");
        assertThat(page).doesNotContain("??project.", "??redis.");
    }

    @Test
    void editingARedisProjectPreselectsItsTarget() throws Exception {
        Project project = new Project();
        project.setId(6L);
        project.setName("cache-proj");
        project.setSourceDbId(1L);
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(5L);
        when(projectService.findById(6L)).thenReturn(Optional.of(project));
        when(projectService.isRunning(project)).thenReturn(false);
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE))
                .thenReturn(List.of(config(1L, "src", ConnectionRole.SOURCE)));
        when(databaseConfigService.findByRole(ConnectionRole.TARGET)).thenReturn(List.of());
        when(redisTargetService.findAll()).thenReturn(List.of(redisTarget(5L, "cache-1")));

        String page = mvc.perform(get("/projects/6/edit").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).containsPattern("value=\"REDIS\"[^>]*checked");
        String redisSelect = selectBlock(page, "targetRedisId");
        assertThat(redisSelect).containsPattern("value=\"5\"[^>]*selected");
        assertThat(redisSelect).contains("required");
        assertThat(selectBlock(page, "targetDbId")).doesNotContain("required");
    }

    private static RedisTarget redisTarget(long id, String name) {
        RedisTarget target = new RedisTarget();
        target.setId(id);
        target.setName(name);
        target.setHost("redis.local");
        target.setPort(6379);
        return target;
    }
}
