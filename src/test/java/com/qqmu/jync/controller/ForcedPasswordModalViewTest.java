package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The forced password change happens on the home page, in the layout's password modal.
 *
 * <p>While the account still carries the seeded password the modal is rendered open by the
 * server (no flash of an unlocked page) and offers no way to close it — no close button, no
 * cancel button, no mask-click target, and the dashboard behind it is not computed at all, so
 * the public default password cannot be used to read project statistics either.
 */
@WebMvcTest(DashboardController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class})
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ForcedPasswordModalViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DashboardService dashboardService;

    private String render(boolean usingDefaultPassword) throws Exception {
        return mvc.perform(get("/dashboard")
                        .header("Accept-Language", "zh-CN")
                        .with(as(usingDefaultPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theForcedHomePageOpensThePasswordModalAndCannotCloseIt() throws Exception {
        String html = render(true);
        String modal = modalBlock(html);

        assertThat(modal).contains("open");
        assertThat(modal).contains("data-forced=\"true\"");
        assertThat(modal).contains("aria-hidden=\"false\"");
        // Every dismissal route the modal normally has must be gone.
        assertThat(modal).doesNotContain("data-close-modal");
        assertThat(modal).doesNotContain("modal-close");
        // The cancel button is the only btn-ghost in this modal.
        assertThat(modal).doesNotContain("btn-ghost");
        assertThat(html).contains("请先修改密码");
        assertThat(html).doesNotContain("??");
    }

    @Test
    void theForcedHomePageDoesNotComputeOrRenderTheDashboard() throws Exception {
        String html = render(true);

        verify(dashboardService, never()).collect();
        assertThat(html).doesNotContain("stat-grid");
    }

    @Test
    void anAccountThatChangedItsPasswordGetsTheOrdinaryDashboard() throws Exception {
        when(dashboardService.collect()).thenReturn(new DashboardService.Stats());

        String html = render(false);
        String modal = modalBlock(html);

        assertThat(html).contains("stat-grid");
        assertThat(modal).doesNotContain("data-forced");
        assertThat(modal).doesNotContainPattern("class=\"modal open\"");
        assertThat(modal).contains("data-close-modal=\"cp-modal\"");
        assertThat(modal).contains("modal-close");
    }

    /** Runs from the modal's opening tag to the next modal, so assertions cannot leak out. */
    private static String modalBlock(String html) {
        int id = html.indexOf("id=\"cp-modal\"");
        assertThat(id).as("cp-modal is rendered").isGreaterThanOrEqualTo(0);
        // Start at the tag itself: the class attribute (carrying "open") precedes the id.
        int start = html.lastIndexOf("<div", id);
        int end = html.indexOf("id=\"donate-modal\"");
        return end > start ? html.substring(start, end) : html.substring(start);
    }

    private static RequestPostProcessor as(boolean usingDefaultPassword) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, usingDefaultPassword);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
