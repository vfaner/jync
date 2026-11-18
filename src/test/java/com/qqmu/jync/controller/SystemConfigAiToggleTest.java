package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.SystemSetting;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.SystemSettingRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The page switch for the AI feature, end to end: the row lands in {@code system_setting}, the
 * audit trail names the old and new value, and it can be flipped either way.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ai-toggle;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/ai-toggle-logs/jync.log"
})
class SystemConfigAiToggleTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SystemSettingRepository settings;
    @Autowired
    private AdminAuditService audits;

    /** Both tests share one in-memory database; each starts from a fresh settings table so the
     *  "nothing was written" assertion cannot see the other test's row. */
    @BeforeEach
    void clearSettings() {
        settings.deleteAll();
    }

    private static RequestPostProcessor as(String username, UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(username);
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    @Test
    void anAdminTurnsTheSwitchOffAndBackOn() throws Exception {
        mvc.perform(post("/system-config/ai-enabled").param("enabled", "false")
                        .with(as("admin", UserRole.ADMIN)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.ai.disabled"));

        assertThat(settings.findById("ai.enabled"))
                .get().extracting(SystemSetting::getValue).isEqualTo("false");
        assertThat(audits.page(0, 20).getContent()).anySatisfy(event -> {
            assertThat(event.getAction()).isEqualTo(AuditAction.SYSTEM_SETTING_SAVE);
            assertThat(event.getDetail()).contains("ai.enabled").contains("|false");
        });

        mvc.perform(post("/system-config/ai-enabled").param("enabled", "true")
                        .with(as("admin", UserRole.ADMIN)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "msg.systemConfig.ai.enabled"));

        assertThat(settings.findById("ai.enabled"))
                .get().extracting(SystemSetting::getValue).isEqualTo("true");
    }

    @Test
    void anOperatorCannotFlipIt() throws Exception {
        mvc.perform(post("/system-config/ai-enabled").param("enabled", "false")
                        .with(as("bob", UserRole.OPS)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?denied"));

        assertThat(settings.findById("ai.enabled")).isEmpty();
    }
}
