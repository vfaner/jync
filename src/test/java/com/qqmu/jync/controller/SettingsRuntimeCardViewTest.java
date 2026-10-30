package com.qqmu.jync.controller;

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

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.version.VersionInfo;
import com.qqmu.jync.service.version.VersionService;

/**
 * The 运行时信息 card names the machine the numbers elsewhere describe.
 *
 * <p>It is also where the resource gauges used to live; they moved to the dashboard as a bare
 * row, so this page must not carry a polling block any more.
 */
@WebMvcTest(SettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "sync.reconcile-interval-ms=30000")
class SettingsRuntimeCardViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SyncScheduler scheduler;
    @MockBean
    private SyncLockService lockService;
    @MockBean
    private VersionService versionService;

    private String render(Locale locale) throws Exception {
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(versionService.snapshot()).thenReturn(VersionInfo.builder()
                .currentVersion("1.8.1")
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.8.1").latestTag("v1.8.1")
                .build());

        return mvc.perform(get("/settings").locale(locale).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theRuntimeCardNamesTheOperatingSystem() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("操作系统").contains(System.getProperty("os.name"));
    }

    @Test
    void englishRendersTheEnglishWording() throws Exception {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("Operating system").doesNotContain("操作系统");
    }

    @Test
    void theRuntimeCardShowsWhenTheApplicationStarted() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE);

        // A real clock value, not a placeholder: the row is only useful if it carries a time.
        assertThat(html).containsPattern("(?s)启动时间.{0,500}?\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void theResourceGaugesAreNoLongerOnThisPage() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).doesNotContain("resource-gauges");
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
