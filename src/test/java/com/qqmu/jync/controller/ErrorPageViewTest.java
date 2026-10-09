package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.servlet.RequestDispatcher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The branded error page behind {@code /error}, rendered for real.
 *
 * <p>Before this existed an unhandled exception fell through to the whitelabel page, which leaks
 * the framework and breaks every visual convention of the app. MockMvc has no container ERROR
 * dispatch, so the request arrives at {@code /error} carrying the same
 * {@link RequestDispatcher} attributes a real container would set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:errorpage;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class ErrorPageViewTest {

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        return authentication(token);
    }

    /** Simulates the container forward after an error: status code request attr + HTML accept. */
    private String renderError(int statusCode, String acceptLanguage) throws Exception {
        return mvc.perform(get("/error")
                        .accept(MediaType.TEXT_HTML)
                        .header("Accept-Language", acceptLanguage)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, statusCode)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/projects/1")
                        .with(asAdmin()))
                .andExpect(status().is(statusCode))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void errorPathRendersTheBrandedPageInEnglish() throws Exception {
        String html = renderError(500, "en-US");

        assertThat(html).contains("Something went wrong");
        assertThat(html).contains("Back to home");
        assertThat(html).doesNotContain("??");
    }

    @Test
    void errorPathRendersTheBrandedPageInChinese() throws Exception {
        String html = renderError(500, "zh-CN");

        assertThat(html).contains("页面出了点问题");
        assertThat(html).contains("返回首页");
        assertThat(html).doesNotContain("??");
    }

    @Test
    void theStatusAttributeIsShownWhenPresent() throws Exception {
        String html = renderError(404, "en-US");

        assertThat(html).contains("Something went wrong");
        assertThat(html).contains("HTTP 404");
    }
}
