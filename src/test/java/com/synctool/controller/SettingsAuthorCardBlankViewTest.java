package com.synctool.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.synctool.model.AppUser;
import com.synctool.model.UserRole;
import com.synctool.service.auth.SyncUserDetails;
import com.synctool.service.task.SyncLockService;
import com.synctool.service.task.SyncScheduler;
import com.synctool.service.version.VersionInfo;
import com.synctool.service.version.VersionService;

/**
 * An internal deployment blanks the Gitee mirror and the contact handles by overriding
 * them to empty strings. The author card must then keep its GitHub row and drop every
 * blank one, instead of rendering labels with nothing behind them.
 */
@WebMvcTest(SettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = {
        "app.github-url=https://github.com/vfaner/synctool",
        "app.gitee-url=",
        "app.contact-qq=",
        "app.contact-wechat="})
class SettingsAuthorCardBlankViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SyncScheduler scheduler;
    @MockBean
    private SyncLockService lockService;
    @MockBean
    private VersionService versionService;

    @Test
    void blankConfigKeepsTheGithubRowAndDropsTheRest() throws Exception {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        RequestPostProcessor auth = authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));

        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(VersionInfo.builder()
                .state(VersionInfo.State.UP_TO_DATE).build());

        String page = mvc.perform(get("/settings").locale(Locale.SIMPLIFIED_CHINESE).with(auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("项目与作者");
        assertThat(page).contains("https://github.com/vfaner/synctool");
        assertThat(page).doesNotContain("/assets/gitee.ico");
        // Match the dt tags: the donate dialog has its own 微信/QQ pay tabs.
        assertThat(page).doesNotContain(">Gitee</dt>");
        assertThat(page).doesNotContain(">QQ</dt>");
        assertThat(page).doesNotContain(">微信</dt>");
    }
}
