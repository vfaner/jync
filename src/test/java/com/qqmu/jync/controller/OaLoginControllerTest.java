package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.auth.oa.OaAuthClient;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;
import com.qqmu.jync.service.auth.oa.OaAuthResult;

/**
 * The OA sign-in POST, against the real filter chain: it is reachable without a session, it
 * persists an authenticated context into the session, and it counts failures the same way the
 * local form does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:oalogintest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class OaLoginControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private OaAuthClient oaAuthClient;
    @MockBean
    private OaAuthConfigService configService;
    @MockBean
    private AdminAuditService auditService;

    private OaAuthConfig config;

    @BeforeEach
    void setUp() {
        config = new OaAuthConfig();
        config.setEnabled(true);
        config.setLoginUrl("https://oa.example.com/login");
        when(configService.load()).thenReturn(config);
    }

    @Test
    void whenOaIsDisabledTheEndpointTurnsPeopleAway() throws Exception {
        config.setEnabled(false);

        mvc.perform(post("/login/oa").with(csrf())
                        .param("username", "alice").param("password", "pw"))
                .andExpect(redirectedUrl("/login?oaDisabled"));

        verify(oaAuthClient, never()).authenticate(any(), anyString(), anyString());
    }

    @Test
    void aSuccessfulSignInStoresAnExternalSessionAndGoesToTheSavedRequest() throws Exception {
        when(oaAuthClient.authenticate(any(), eq("alice"), eq("pw")))
                .thenReturn(OaAuthResult.success("alice", false));

        MockHttpSession session = new MockHttpSession();
        // Signing in from a deep link: the request cache keeps the target in the session.
        mvc.perform(get("/projects").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        String sessionIdBefore = session.getId();

        // The saved target is reduced to a same-origin path (no absolute URL can be replayed).
        mvc.perform(post("/login/oa").session(session).with(csrf())
                        .param("username", "alice").param("password", "pw"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects"));

        // Session fixation protection: the id the browser arrived with is not the id it leaves with.
        assertThat(session.getId()).isNotEqualTo(sessionIdBefore);

        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().getName()).isEqualTo("alice");
        assertThat(context.getAuthentication().getAuthorities())
                .extracting(Object::toString).contains("ROLE_VIEWER");

        SyncUserDetails principal = (SyncUserDetails) context.getAuthentication().getPrincipal();
        assertThat(principal.isExternal()).isTrue();
        assertThat(principal.isUsingDefaultPassword()).isFalse();

        verify(auditService).record(eq(AuditAction.LOGIN), anyString(), eq("alice"));
    }

    @Test
    void anAdminMarkerGrantsTheAdminRole() throws Exception {
        when(oaAuthClient.authenticate(any(), eq("root"), eq("pw")))
                .thenReturn(OaAuthResult.success("root", true));

        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/login/oa").session(session).with(csrf())
                        .param("username", "root").param("password", "pw"))
                .andExpect(redirectedUrl("/"));

        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        SyncUserDetails principal = (SyncUserDetails) context.getAuthentication().getPrincipal();
        assertThat(principal.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(principal.isAdmin()).isTrue();
    }

    @Test
    void aRejectedSignInIsCountedAndAudited() throws Exception {
        when(oaAuthClient.authenticate(any(), eq("mallory"), eq("bad")))
                .thenReturn(OaAuthResult.failure("error.oa.rejected"));

        mvc.perform(post("/login/oa").with(csrf())
                        .param("username", "mallory").param("password", "bad"))
                .andExpect(redirectedUrl("/login?oaError"));

        verify(auditService).record(eq(AuditAction.LOGIN_FAILED), anyString(), eq("mallory"));
    }

    @Test
    void afterFiveFailuresThePairIsLockedAndTheOaIsNotCalledAgain() throws Exception {
        when(oaAuthClient.authenticate(any(), eq("bruteforce"), anyString()))
                .thenReturn(OaAuthResult.failure("error.oa.rejected"));

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login/oa").with(csrf())
                            .param("username", "bruteforce").param("password", "guess" + i))
                    .andExpect(redirectedUrl("/login?oaError"));
        }

        mvc.perform(post("/login/oa").with(csrf())
                        .param("username", "bruteforce").param("password", "right"))
                .andExpect(redirectedUrl("/login?oaLocked"));

        verify(oaAuthClient, times(5)).authenticate(any(), anyString(), anyString());
        verify(auditService, times(5)).record(eq(AuditAction.LOGIN_FAILED), anyString(), eq("bruteforce"));
    }

    @Test
    void theConfiguredAdminListAlsoAppliesToPasswordMode() throws Exception {
        config.setAdminUsers("root");
        when(oaAuthClient.authenticate(any(), eq("root"), eq("pw")))
                .thenReturn(OaAuthResult.success("root", false));

        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/login/oa").session(session).with(csrf())
                        .param("username", "root").param("password", "pw"))
                .andExpect(redirectedUrl("/"));

        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        SyncUserDetails principal = (SyncUserDetails) context.getAuthentication().getPrincipal();
        assertThat(principal.isAdmin()).isTrue();
    }

    @Test
    void anOaCallThatNeverRanStillNeedsTheCsrfToken() throws Exception {
        // CSRF rejects before the handler; the app's access-denied handler answers HTML with a
        // redirect to /?denied rather than a bare 403.
        mvc.perform(post("/login/oa")
                        .param("username", "alice").param("password", "pw"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?denied"));

        verify(oaAuthClient, never()).authenticate(any(), anyString(), anyString());
    }
}
