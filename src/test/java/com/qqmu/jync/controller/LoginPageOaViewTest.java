package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.service.auth.oa.OaAuthConfigService;

/**
 * The sign-in page offers the OA form only when the administrator enabled it.
 *
 * <p>Runs against the real filter chain: the default chain a {@code @WebMvcTest} slice builds
 * serves its own generated login page at {@code /login}, hiding the controller under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:loginpageoa;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class LoginPageOaViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private OaAuthConfigService configService;

    private String loginPage(String query) throws Exception {
        return mvc.perform(get("/login" + query).locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anEnabledOaAddsASecondFormWithoutReplacingTheLocalOne() throws Exception {
        when(configService.isEnabled()).thenReturn(true);

        String page = loginPage("");

        assertThat(page).contains("action=\"/login\"", "action=\"/login/oa\"");
        assertThat(page).contains("id=\"oa-username\"", "id=\"oa-password\"");
        assertThat(page).contains("使用 OA 账号登录");
    }

    @Test
    void aDisabledOaRendersOnlyTheLocalForm() throws Exception {
        when(configService.isEnabled()).thenReturn(false);

        String page = loginPage("");

        assertThat(page).contains("action=\"/login\"");
        assertThat(page).doesNotContain("/login/oa", "id=\"oa-username\"");
    }

    @Test
    void theOaOutcomesHaveTheirOwnAlerts() throws Exception {
        when(configService.isEnabled()).thenReturn(true);

        assertThat(loginPage("?oaError")).contains("OA 账号或密码不正确");
        assertThat(loginPage("?oaLocked")).contains("失败次数过多");
        assertThat(loginPage("?oaDisabled")).contains("OA 登录未启用");
    }
}
