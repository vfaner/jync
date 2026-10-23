package com.qqmu.jync.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.AppUserService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;

/**
 * An OA user has no local password to change. The template hides the entry, and these two
 * server-side guards keep a hand-written GET/POST out even if it were shown.
 */
@WebMvcTest(AuthController.class)
class AuthControllerExternalUserTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AppUserService userService;
    /** AuthController reads this on /login to decide whether to show the OA form. */
    @MockBean
    private OaAuthConfigService oaConfigService;

    private static UsernamePasswordAuthenticationToken externalAuth() {
        SyncUserDetails principal = SyncUserDetails.forExternalUser("alice", UserRole.VIEWER);
        return new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }

    @Test
    void anExternalUserCannotOpenTheChangePasswordForm() throws Exception {
        mvc.perform(get("/account/password").with(authentication(externalAuth())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?denied"));

        verifyNoInteractions(userService);
    }

    @Test
    void anExternalUserCannotSubmitTheChangePasswordForm() throws Exception {
        mvc.perform(post("/account/password").with(authentication(externalAuth())).with(csrf())
                        .param("currentPassword", "x")
                        .param("newPassword", "newpass")
                        .param("confirmPassword", "newpass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?denied"));

        verifyNoInteractions(userService);
    }

    @Test
    void aLocalUserStillReachesTheChangePasswordForm() throws Exception {
        com.qqmu.jync.model.AppUser user = new com.qqmu.jync.model.AppUser();
        user.setId(1L);
        user.setUsername("local");
        user.setPasswordHash("hash");
        user.setRole(UserRole.VIEWER);
        SyncUserDetails principal = new SyncUserDetails(user, false);

        mvc.perform(get("/account/password").with(authentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()))))
                .andExpect(status().isOk());
    }
}
