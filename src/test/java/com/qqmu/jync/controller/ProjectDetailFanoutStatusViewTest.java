package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.FanoutPosition;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.fanout.FanoutPositionStore;
import com.qqmu.jync.service.fanout.FanoutRuntime;
import com.qqmu.jync.service.fanout.FanoutRuntimeRegistry;
import com.qqmu.jync.service.projection.redis.RedisTargetService;

/**
 * The polled fan-out status fragment. A target that has not acknowledged anything yet has no
 * binlog file and no age; the fragment must still render every target (the fragment is fetched
 * every five seconds by the detail page, so a missing model key would 500 the whole row list).
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectDetailFanoutStatusViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private DatabaseConfigService databaseConfigService;
    @MockitoBean private CdcOperationsService cdcOperationsService;
    @MockitoBean private CdcConsumerManager cdcConsumerManager;
    @MockitoBean private CdcPositionStore cdcPositionStore;
    @MockitoBean private RedisTargetService redisTargetService;
    @MockitoBean private FanoutPositionStore fanoutPositionStore;
    @MockitoBean private FanoutRuntimeRegistry fanoutRuntimes;

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

    private SyncConfig projectWithFanout() {
        Project project = new Project();
        project.setId(1L);
        project.setName("fan");
        project.setTargetDbId(2L);
        SyncConfig config = new SyncConfig();
        config.setFanoutTargetDbIds(List.of(3L));
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(config);
        when(databaseConfigService.findById(anyLong())).thenReturn(Optional.empty());
        return config;
    }

    @Test
    void targetsWithAndWithoutAPositionBothRender() throws Exception {
        projectWithFanout();
        FanoutPosition consumed = new FanoutPosition();
        consumed.setProjectId(1L);
        consumed.setTargetDbId(3L);
        consumed.setBinlogFile("mysql-bin.000007");
        consumed.setBinlogPos(1024L);
        consumed.setUpdatedAt(Instant.now().minusSeconds(30));
        when(fanoutPositionStore.find(1L, 2L)).thenReturn(Optional.empty());
        when(fanoutPositionStore.find(1L, 3L)).thenReturn(Optional.of(consumed));

        String html = mvc.perform(get("/projects/1/fanout-status").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("#2", "#3");
        assertThat(html).contains("mysql-bin.000007:1024");
        assertThat(html).contains("fanout-status-list");
        // Size is rendered in human units and styled apart from the muted notes.
        assertThat(html).contains("fanout-spool-metric");
    }

    @Test
    void aRedisTransportProjectShowsTheQueueInsteadOfASpoolSize() throws Exception {
        SyncConfig config = projectWithFanout();
        config.setFanoutTransport("REDIS");
        when(fanoutPositionStore.find(1L, 2L)).thenReturn(Optional.empty());
        when(fanoutPositionStore.find(1L, 3L)).thenReturn(Optional.empty());

        String html = mvc.perform(get("/projects/1/fanout-status").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Redis Stream");
        assertThat(html).doesNotContain("spool");
    }

    @Test
    void liveWorkerStateIsShownNextToThePersistedPosition() throws Exception {
        projectWithFanout();
        when(fanoutPositionStore.find(1L, 2L)).thenReturn(Optional.empty());
        when(fanoutPositionStore.find(1L, 3L)).thenReturn(Optional.empty());
        FanoutRuntime runtime = org.mockito.Mockito.mock(FanoutRuntime.class);
        when(fanoutRuntimes.get(1L)).thenReturn(runtime);
        when(runtime.workerStates()).thenReturn(List.of(
                new FanoutRuntime.TargetState(2L, true, 12L, null, null),
                new FanoutRuntime.TargetState(3L, false, 0L, "table 'orders' is gone", null)));

        String html = mvc.perform(get("/projects/1/fanout-status").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The stopped worker's error is the actionable bit; the applied count proves the
        // healthy target is moving even when the persisted position has not changed yet.
        assertThat(html).contains("table &#39;orders&#39; is gone");
        assertThat(html).contains("12");
    }

    @Test
    void aProjectWithoutFanoutTargetsRendersTheEmptyFragment() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setTargetDbId(2L);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());

        String html = mvc.perform(get("/projects/1/fanout-status").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The fragment shell still renders (the page polls it), but it lists no target rows.
        assertThat(html).contains("fanout-status-list");
        assertThat(html).doesNotContain("<li");
    }
}
