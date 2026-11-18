package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.SystemSettingRepository;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * {@code sync.ai.enabled=false} is the deployment's hard off: the page card is read-only and a
 * POST is refused without writing anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ai-hard-off;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/ai-hard-off-logs/jync.log"
})
class SystemConfigAiHardOffTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SystemSettingRepository settings;

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

    @Test
    void theSwitchIsRefusedAndNothingIsWritten() throws Exception {
        mvc.perform(post("/system-config/ai-enabled").param("enabled", "true")
                        .with(asAdmin()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.systemConfig.ai.envControlled"));

        assertThat(settings.findById("ai.enabled")).isEmpty();
    }

    @Test
    void theCardExplainsTheDeploymentPinsIt() throws Exception {
        String page = mvc.perform(get("/system-config")
                        .locale(Locale.SIMPLIFIED_CHINESE).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("AI 辅助")
                .contains("action=\"/system-config/ai-enabled\"")
                .contains("sync.ai.enabled");
        int form = page.indexOf("action=\"/system-config/ai-enabled\"");
        assertThat(page.substring(form, page.indexOf("</form>", form))).contains("disabled");
    }
}
