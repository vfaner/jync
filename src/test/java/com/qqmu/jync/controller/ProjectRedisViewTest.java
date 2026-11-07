package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisTargetService;
import com.qqmu.jync.service.projection.redis.RedisValueFormat;

/**
 * A Redis target's detail page is a cache-projection page, not a database page: no DDL
 * switches, no cron, no batch size, and the strategy list offers only log CDC. The inputs are
 * asserted by {@code name=} rather than by localized copy so the check does not depend on the
 * bundle the test happens to render.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectRedisViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private DatabaseConfigService databaseConfigService;
    @MockitoBean private CdcOperationsService cdcOperationsService;
    @MockitoBean private CdcConsumerManager cdcConsumerManager;
    @MockitoBean private CdcPositionStore cdcPositionStore;
    @MockitoBean private RedisTargetService redisTargetService;

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

    @Test
    void detailShowsRedisTargetInsteadOfJdbcSections() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setName("cache-proj");
        project.setSourceDbId(2L);
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(9L);
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));

        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        config.getTables().add("orders");
        RedisProjectionConfig projection = new RedisProjectionConfig();
        projection.setKeyPrefix("jync:1:");
        projection.setValueFormat(RedisValueFormat.HASH);
        projection.setTtlSeconds(3600);
        config.setRedisProjection(projection);
        when(projectService.loadConfig(project)).thenReturn(config);

        DatabaseConfig source = new DatabaseConfig();
        source.setId(2L);
        source.setName("mysql-src");
        source.setType(DatabaseType.MYSQL);
        when(databaseConfigService.findById(2L)).thenReturn(Optional.of(source));

        RedisTarget target = new RedisTarget();
        target.setId(9L);
        target.setName("cache-1");
        target.setHost("redis-1.local");
        target.setPort(6379);
        when(redisTargetService.find(9L)).thenReturn(Optional.of(target));

        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        SyncProgress progress = new SyncProgress();
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName("orders");
        when(projectService.findProgress(1L)).thenReturn(List.of(progress));
        when(projectService.findFullLoadProgress(1L)).thenReturn(Map.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        ProjectService.SourceObjects objects = new ProjectService.SourceObjects();
        objects.getTables().add("orders");
        when(projectService.listSourceObjects(1L)).thenReturn(objects);

        String page = mvc.perform(get("/projects/1").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The Redis projection card, with the saved contract.
        assertThat(page).contains("name=\"keyPrefix\"");
        assertThat(page).contains("value=\"jync:1:\"");
        assertThat(page).contains("name=\"valueFormat\"");
        assertThat(page).contains("name=\"ttlSeconds\"");
        assertThat(page).contains("cache-1");
        assertThat(page).contains("redis-1.local:6379");

        // Only CDC strategies are offered; polling and full compare are not.
        assertThat(page).contains("value=\"BINLOG_CDC\"");
        assertThat(page).doesNotContain("value=\"POLLING\"");
        assertThat(page).doesNotContain("value=\"FULL_COMPARE\"");

        // JDBC-only sections are gone, not merely disabled.
        assertThat(page).doesNotContain("name=\"syncStructure\"");
        assertThat(page).doesNotContain("name=\"syncIndexes\"");
        assertThat(page).doesNotContain("name=\"syncViews\"");
        assertThat(page).doesNotContain("name=\"cronExpression\"");
        assertThat(page).doesNotContain("name=\"pollIntervalMs\"");
        assertThat(page).doesNotContain("name=\"batchSize\"");
        assertThat(page).doesNotContain("name=\"disableTargetConstraints\"");

        // The table selection stays: it is what the projection mirrors.
        assertThat(page).contains("name=\"tables\"");

        // Actions that read a JDBC target have none to read here: the target preview would
        // ask for a targetDbId this project does not have, and the content audit compares
        // source rows against a relational target that does not exist.
        assertThat(page).doesNotContain("data-side=\"target\"");
        assertThat(page).doesNotContain("data-role=\"content-audit\"");
    }

    /**
     * A cache page does not render the schedule or full-load performance cards, so those
     * fields are absent from its submission. Writing the absent-field defaults would reset
     * the batch size and worker count the projection's baseline load actually runs with —
     * values a converted project saved while it still had a database target.
     */
    @Test
    void savingACacheConfigLeavesFieldsItsPageNeverRenderedAlone() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setName("cache-proj");
        project.setSourceDbId(2L);
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(9L);
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));

        SyncConfig stored = new SyncConfig();
        stored.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        stored.setBatchSize(1500);
        stored.setFullLoadParallelism(3);
        stored.setAllowDrop(true);
        stored.setCronExpression("0 */5 * * * ?");
        when(projectService.loadConfig(project)).thenReturn(stored);

        mvc.perform(post("/projects/1/config").with(asAdmin()).with(csrf())
                        .param("tables", "orders")
                        .param("objectSelectionExplicit", "true")
                        .param("captureStrategy", "BINLOG_CDC"))
                .andExpect(status().is3xxRedirection());

        org.mockito.ArgumentCaptor<SyncConfig> captor =
                org.mockito.ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService).saveConfig(eq(1L), captor.capture(), eq("BINLOG_CDC"));
        SyncConfig saved = captor.getValue();
        assertThat(saved.getBatchSize()).isEqualTo(1500);
        assertThat(saved.getFullLoadParallelism()).isEqualTo(3);
        assertThat(saved.isAllowDrop()).isTrue();
        assertThat(saved.getCronExpression()).isEqualTo("0 */5 * * * ?");
    }

    @Test
    void aJdbcProjectStillRendersTheDatabaseSections() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setName("db-proj");
        project.setSourceDbId(2L);
        project.setTargetDbId(3L);
        project.setTargetKind(SyncTargetKind.JDBC);
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));

        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);
        when(projectService.loadConfig(project)).thenReturn(config);

        DatabaseConfig source = new DatabaseConfig();
        source.setId(2L);
        source.setName("mysql-src");
        source.setType(DatabaseType.MYSQL);
        when(databaseConfigService.findById(2L)).thenReturn(Optional.of(source));
        DatabaseConfig target = new DatabaseConfig();
        target.setId(3L);
        target.setName("pg-tgt");
        target.setType(DatabaseType.POSTGRESQL);
        when(databaseConfigService.findById(3L)).thenReturn(Optional.of(target));

        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.findFullLoadProgress(1L)).thenReturn(Map.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());

        String page = mvc.perform(get("/projects/1").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain("name=\"keyPrefix\"");
        assertThat(page).contains("value=\"POLLING\"");
        assertThat(page).contains("name=\"syncStructure\"");
    }
}
