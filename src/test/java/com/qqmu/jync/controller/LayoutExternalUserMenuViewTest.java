package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/** The user menu offers "change password" only to accounts that actually live in this database. */
@WebMvcTest(DatabaseConfigController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/jync")
class LayoutExternalUserMenuViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DatabaseConfigService databaseConfigService;
    @MockBean
    private BuildProperties buildProperties;

    private static RequestPostProcessor asLocal() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("local");
        user.setPasswordHash("hash");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static RequestPostProcessor asExternal() {
        SyncUserDetails principal = SyncUserDetails.forExternalUser("alice", UserRole.VIEWER);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private String render(RequestPostProcessor auth) throws Exception {
        when(databaseConfigService.findPageByRole(any(), anyInt(), anyInt()))
                .thenReturn(Page.empty());
        return mvc.perform(get("/databases").locale(Locale.SIMPLIFIED_CHINESE).with(auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anExternalUserGetsNoChangePasswordEntry() throws Exception {
        String page = render(asExternal());

        assertThat(page).doesNotContain("data-open-modal=\"cp-modal\"");
        // Signing out still has to work: that is the only exit an OA user has.
        assertThat(page).contains("id=\"logout-btn\"");
    }

    @Test
    void aLocalUserKeepsTheChangePasswordEntry() throws Exception {
        assertThat(render(asLocal())).contains("data-open-modal=\"cp-modal\"");
    }
}
