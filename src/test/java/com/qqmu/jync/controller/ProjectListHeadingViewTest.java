package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisTargetService;

/**
 * The list page sits under the 实时同步 menu entry, but its heading said only "同步任务" —
 * the same words as the menu group that holds every sync feature, so the page named the
 * section rather than itself. The heading now names the real-time task list.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectListHeadingViewTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private DatabaseConfigService databaseConfigService;

    @MockitoBean
    private CdcOperationsService cdcOperationsService;

    @MockitoBean
    private CdcConsumerManager cdcConsumerManager;

    @MockitoBean
    private CdcPositionStore cdcPositionStore;

    @MockitoBean
    private RedisTargetService redisTargetService;

    @Test
    void theHeadingNamesRealTimeSyncTasksInChinese() throws Exception {
        String html = page(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("<h1>实时同步任务</h1>");
    }

    @Test
    void theHeadingNamesRealTimeSyncTasksInEnglish() throws Exception {
        String html = page(Locale.ENGLISH);

        assertThat(html).contains("<h1>Real-time sync tasks</h1>");
    }

    private String page(Locale locale) throws Exception {
        when(projectService.count()).thenReturn(0L);
        when(projectService.findPage(any(Integer.class), any(Integer.class)))
                .thenReturn(List.of());
        when(projectService.findTasks(any())).thenReturn(Map.of());

        return mvc.perform(get("/projects").locale(locale).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
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
