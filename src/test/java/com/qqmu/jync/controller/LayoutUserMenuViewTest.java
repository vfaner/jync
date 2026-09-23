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
 * The navbar user chip is a dropdown trigger: change-password opens the cp-modal in place,
 * sign-out submits a hidden POST form (CSRF field rendered server-side). Both roles get the
 * menu — viewers may change their own password too.
 */
@WebMvcTest(SettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/jync")
class LayoutUserMenuViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SyncScheduler scheduler;
    @MockBean
    private SyncLockService lockService;
    @MockBean
    private VersionService versionService;

    private static RequestPostProcessor as(UserRole role) {
        return as(role, false);
    }

    private static RequestPostProcessor as(UserRole role, boolean usingDefaultPassword) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role == UserRole.ADMIN ? "admin" : "view");
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, usingDefaultPassword);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private String render(UserRole role) throws Exception {
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(VersionInfo.builder()
                .state(VersionInfo.State.UP_TO_DATE).build());
        return mvc.perform(get("/settings").locale(Locale.SIMPLIFIED_CHINESE).with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void userChipIsADropdownTriggerWithBothActions() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("class=\"user-chip nav-trigger\"");
        assertThat(page).contains("aria-haspopup=\"true\"");
        assertThat(page).contains("nav-caret");
        // 修改密码 opens the modal in place; 退出登录 submits the hidden form via JS.
        assertThat(page).containsPattern(
                "class=\"menu-item\"\\s+data-open-modal=\"cp-modal\"");
        assertThat(page).contains("id=\"logout-btn\"");
        assertThat(page).contains("修改密码");
        assertThat(page).contains("退出登录");
    }

    @Test
    void navbarGitHubIconButtonIsGone() throws Exception {
        String page = render(UserRole.ADMIN);

        // The repo link now lives only in the donate dialog (btn classes) and the
        // settings page; the navbar must not render it as an icon-btn anymore.
        assertThat(page).doesNotContain("class=\"icon-btn\" href=\"https://github");
        assertThat(page).doesNotContain("GitHub 仓库");
    }

    @Test
    void passwordModalPostsToTheSameEndpointAsThePage() throws Exception {
        String page = render(UserRole.ADMIN);

        int modal = page.indexOf("id=\"cp-modal\"");
        assertThat(modal).isPositive();
        String form = page.substring(page.indexOf("<form", modal));
        assertThat(form).contains("name=\"currentPassword\"");
        assertThat(form).contains("name=\"newPassword\"");
        assertThat(form).contains("name=\"confirmPassword\"");
        // Spring Security renders the CSRF hidden field for th:action forms.
        assertThat(form).contains("name=\"_csrf\"");
    }

    @Test
    void passwordModalMatchesTheDesign() throws Exception {
        String page = render(UserRole.ADMIN);

        int modal = page.indexOf("id=\"cp-modal\"");
        String head = page.substring(modal, page.indexOf("<form", modal));
        assertThat(head).contains("<h3>修改密码</h3>");
        // 取消 / 确认修改 buttons are right-aligned through .modal-actions.
        String form = page.substring(page.indexOf("<form", modal));
        assertThat(form).contains("class=\"modal-actions\"");
        assertThat(form).contains(">确认修改</span>");
        assertThat(form).contains("新密码长度至少 6 位");
        assertThat(form).contains(">原密码<");
    }

    @Test
    void logoutFormIsHiddenAndCarriesCsrf() throws Exception {
        String page = render(UserRole.ADMIN);

        int form = page.indexOf("id=\"logout-form\"");
        assertThat(form).isPositive();
        String tag = page.substring(form, page.indexOf("</form>", form));
        assertThat(tag).contains("method=\"post\"");
        assertThat(tag).contains("name=\"_csrf\"");
    }

    @Test
    void viewerGetsTheSameMenu() throws Exception {
        String page = render(UserRole.VIEWER);

        assertThat(page).contains("data-open-modal=\"cp-modal\"");
        assertThat(page).contains("id=\"logout-btn\"");
    }
}
