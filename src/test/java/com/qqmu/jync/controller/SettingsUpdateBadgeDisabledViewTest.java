package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.version.VersionInfo;
import com.qqmu.jync.service.version.VersionService;

/**
 * With {@code app.update-check.enabled=false} the settings page must not even render the
 * hidden nav badge: there is no check to trigger, and an offline/air-gapped deployment
 * should never see an update prompt.
 */
@WebMvcTest(SettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = {
        "app.github-url=https://github.com/vfaner/jync",
        "app.update-check.enabled=false"
})
class SettingsUpdateBadgeDisabledViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private SyncScheduler scheduler;
    @MockBean private SyncLockService lockService;
    @MockBean private VersionService versionService;
    @MockBean private BuildProperties buildProperties;

    @Test
    void noNavBadgeIsRenderedWhenTheUpdateCheckIsSwitchedOff() throws Exception {
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(VersionInfo.builder()
                .currentVersion("1.7.6")
                .state(VersionInfo.State.DISABLED)
                .build());

        String page = mvc.perform(get("/settings").locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("已关闭更新检查");
        assertThat(page).doesNotContain("nav-update-badge");
        assertThat(page).doesNotContain("/api/version/check");
    }
}
