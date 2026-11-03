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
 * The donate dialog's support notice links out to the public sponsor board on the official
 * site, so the sponsor list and acknowledgements are maintained in one place instead of being
 * copied into the app. With no site URL configured the link is omitted rather than rendered
 * as a dead relative path.
 */
@WebMvcTest(SettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = {
        "app.github-url=https://github.com/vfaner/jync",
        "app.site-url=https://jync.qqmu.com"
})
class DonateModalViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SyncScheduler scheduler;
    @MockBean
    private SyncLockService lockService;
    @MockBean
    private VersionService versionService;

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

    private String render() throws Exception {
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(VersionInfo.builder()
                .state(VersionInfo.State.UP_TO_DATE).build());
        return mvc.perform(get("/settings").locale(Locale.SIMPLIFIED_CHINESE).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void noticeLinksToTheSponsorBoardOnTheOfficialSite() throws Exception {
        String page = render();

        assertThat(page).contains("class=\"donate-board\"");
        assertThat(page).contains("href=\"https://jync.qqmu.com/donate.html\"");
        assertThat(page).contains("查看赞助名单");
    }

    @Test
    void sponsorBoardLinkOpensInANewTabWithoutOpener() throws Exception {
        String page = render();

        int link = page.indexOf("class=\"donate-board\"");
        String tag = page.substring(link, page.indexOf('>', link));
        assertThat(tag).contains("target=\"_blank\"");
        assertThat(tag).contains("rel=\"noopener noreferrer\"");
    }
}
