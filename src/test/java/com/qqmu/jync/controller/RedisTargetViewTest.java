package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.RedisTargetForm;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.projection.redis.RedisTargetService;

/**
 * The Redis target pages and the shared connection tabs.
 *
 * <p>The decoupling requirement is what most of this pins: the Redis table and form live in
 * their own templates, and the JDBC connection page only gains the tab strip — it must not start
 * rendering Redis fields.
 */
@WebMvcTest({RedisTargetController.class, DatabaseConfigController.class})
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class RedisTargetViewTest {

    private static final Pattern REDIS_TAB_ACTIVE =
            Pattern.compile("data-tab=\"redis\"[^>]*aria-current=\"page\"");
    private static final Pattern JDBC_TAB_ACTIVE =
            Pattern.compile("data-tab=\"jdbc\"[^>]*aria-current=\"page\"");

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RedisTargetService redisTargets;

    @MockBean
    private DatabaseConfigService databaseConfigs;

    @Test
    void theListShowsTheTargetButNeverTheStoredSecret() throws Exception {
        RedisTarget target = target();
        target.setPasswordEnc("enc:top-secret-cipher");
        when(redisTargets.findAll()).thenReturn(List.of(target));

        String page = html(mvc.perform(get("/redis/targets").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        assertThat(page).contains("cache-1").contains("127.0.0.1")
                .doesNotContain("top-secret-cipher")
                .doesNotContain("enc:");
    }

    @Test
    void aViewerGetsNoWriteControlsOnTheRedisList() throws Exception {
        when(redisTargets.findAll()).thenReturn(List.of(target()));

        String page = html(mvc.perform(get("/redis/targets").with(as(UserRole.VIEWER)))
                .andExpect(status().isOk()));

        assertThat(page).doesNotContain("href=\"/redis/targets/new\"")
                .doesNotContain("/redis/targets/3/delete")
                .doesNotContain("/redis/targets/3/edit");
    }

    @Test
    void theEditFormNeverReceivesTheStoredSecret() throws Exception {
        when(redisTargets.formFor(3L)).thenReturn(RedisTargetForm.from(target()));

        String page = html(mvc.perform(get("/redis/targets/3/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        assertThat(page).contains("name=\"rawPassword\"")
                .contains("autocomplete=\"new-password\"")
                .doesNotContain("enc:");
    }

    @Test
    void theNewFormOffersTheRedisConnectionFields() throws Exception {
        String page = html(mvc.perform(get("/redis/targets/new").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        assertThat(page).contains("name=\"host\"")
                .contains("name=\"port\"")
                .contains("name=\"databaseIndex\"")
                .contains("name=\"clusterMode\"")
                .contains("name=\"tlsEnabled\"")
                .contains("name=\"verifyTls\"");
    }

    @Test
    void databaseConnectionsPageShowsBothTabs() throws Exception {
        when(databaseConfigs.findPageByRole(eq(ConnectionRole.SOURCE), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        when(databaseConfigs.findPageByRole(eq(ConnectionRole.TARGET), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        String page = html(mvc.perform(get("/databases").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        assertThat(page).contains("data-role=\"connection-tabs\"")
                .contains("href=\"/redis/targets\"")
                .doesNotContain("??connections.tab");
        assertThat(JDBC_TAB_ACTIVE.matcher(page).find()).isTrue();
    }

    @Test
    void databaseConnectionsPageDoesNotRenderRedisFields() throws Exception {
        when(databaseConfigs.findPageByRole(eq(ConnectionRole.SOURCE), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        when(databaseConfigs.findPageByRole(eq(ConnectionRole.TARGET), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        String page = html(mvc.perform(get("/databases").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        assertThat(page).doesNotContain("name=\"clusterMode\"")
                .doesNotContain("name=\"commandTimeoutMs\"");
    }

    @Test
    void redisTargetsPageHighlightsTheRedisTab() throws Exception {
        when(redisTargets.findAll()).thenReturn(List.of());

        String page = html(mvc.perform(get("/redis/targets").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        assertThat(REDIS_TAB_ACTIVE.matcher(page).find()).isTrue();
    }

    private String html(ResultActions action) throws Exception {
        return action.andReturn().getResponse().getContentAsString();
    }

    private static RedisTarget target() {
        RedisTarget target = new RedisTarget();
        target.setId(3L);
        target.setName("cache-1");
        target.setHost("127.0.0.1");
        target.setPort(6379);
        return target;
    }

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
}
