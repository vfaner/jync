package com.qqmu.jync.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:logoutredirect;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class LogoutRedirectViewTest {

    @Autowired
    private MockMvc mvc;
    @MockBean
    private OaAuthConfigService configService;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asViewer() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("view");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.VIEWER);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    @Test
    void aBlankLogoutTargetFallsBackToTheLoginPage() throws Exception {
        when(configService.load()).thenReturn(new OaAuthConfig());

        mvc.perform(post("/logout").with(csrf()).with(asViewer()))
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void aConfiguredPortalLogoutIsHonoured() throws Exception {
        OaAuthConfig config = new OaAuthConfig();
        config.setLogoutRedirectUrl("https://portal.example.com/logout");
        when(configService.load()).thenReturn(config);

        mvc.perform(post("/logout").with(csrf()).with(asViewer()))
                .andExpect(redirectedUrl("https://portal.example.com/logout"));
    }
}
