package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.AuditEvent;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The admin audit page as served HTML.
 *
 * <p>Every action badge must resolve to a localized label (an unresolved key would render as
 * "??audit.action.X"), and the page must offer no affordance to delete anything — the
 * append-only guarantee is invisible to admins precisely because there is no button.
 * Role enforcement lives in SecurityConfigTest; this slice signs in as ADMIN throughout.
 */
@WebMvcTest(AuditController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class AuditControllerViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private AdminAuditService auditService;

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
        return mvc.perform(get("/audits").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void rowsRenderActorDetailAndALocalizedActionBadge() throws Exception {
        when(auditService.count()).thenReturn(2L);
        when(auditService.page(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(
                AuditEvent.of("admin", AuditAction.LOGIN_FAILED, "BadCredentials from 10.0.0.9"),
                AuditEvent.of("admin", AuditAction.CHANGELOG_CLEAR, "Cleared 42 entries"))));

        String page = render();

        assertThat(page).contains("admin");
        assertThat(page).contains("BadCredentials from 10.0.0.9");
        assertThat(page).contains("Cleared 42 entries");
        assertThat(page).doesNotContain("??audit.action.LOGIN_FAILED",
                "??audit.action.CHANGELOG_CLEAR", "??audit.title", "??nav.audits");
        // Failed sign-ins get the danger badge so a brute-force streak is scannable.
        assertThat(page).contains("badge-danger");
    }

    @Test
    void theEmptyStateExplainsItself() throws Exception {
        when(auditService.count()).thenReturn(0L);
        when(auditService.page(anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        String page = render();

        assertThat(page).contains("empty-ico");
        assertThat(page).doesNotContain("??audit.empty");
    }

    @Test
    void thePageOffersNoWayToRemoveAnEntry() throws Exception {
        when(auditService.count()).thenReturn(1L);
        when(auditService.page(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(
                AuditEvent.of("admin", AuditAction.LOGIN, "Signed in from 127.0.0.1"))));

        String page = render();

        // The layout contributes exactly two POST forms (logout, password modal); the audit
        // page itself must add none — no clear, no delete, no row action of any kind.
        int postForms = java.util.regex.Pattern.compile("method=\"post\"")
                .matcher(page).results().mapToInt(m -> 1).sum();
        assertThat(postForms).isEqualTo(2);
        assertThat(page).doesNotContain("data-role=\"api-btn\"");
    }
}
