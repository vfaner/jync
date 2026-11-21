package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportAssistantService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisTargetService;
import com.qqmu.jync.service.settings.AiAvailability;

/**
 * Entering a page through a nav dropdown must leave that dropdown's own entry marked when the
 * menu is opened again. The group trigger already lit up for any page in the group, but the
 * submenu items carried no active state at all: on /projects the "Real-time sync" entry looked
 * exactly like the two pages the user was not on.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
class NavActiveItemViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private DatabaseConfigService databaseConfigService;
    @MockitoBean private CdcOperationsService cdcOperationsService;
    @MockitoBean private CdcConsumerManager cdcConsumerManager;
    @MockitoBean private CdcPositionStore cdcPositionStore;
    @MockitoBean private RedisTargetService redisTargetService;
    @MockitoBean private SupportAssistantService supportAssistant;
    @MockitoBean private AiAvailability aiAvailability;

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("x");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    @Test
    void theRealtimeEntryIsMarkedOnTheProjectsPageAndItsSiblingsAreNot() throws Exception {
        String html = mvc.perform(get("/projects").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("class=\"menu-item is-active\" href=\"/projects\"");
        assertThat(html).contains("class=\"menu-item\" href=\"/etl\"");
        assertThat(html).contains("class=\"menu-item\" href=\"/migrations\"");
        // The group trigger keeps its own highlight as well.
        assertThat(html).contains("nav-group nav-links-group is-active");
    }

    /**
     * Every dropdown entry must carry the active-state hook for its own page — the rendered
     * test above only covers one group, and a missing hook on another group would silently
     * reproduce the same "nothing selected" symptom there.
     */
    @Test
    void everyDropdownEntryMarksItsOwnPage() throws Exception {
        String layout = Files.readString(
                Path.of("src/main/resources/templates/layout.html"), StandardCharsets.UTF_8);
        String[][] entries = {
                {"/projects", "projects"}, {"/etl", "etl"}, {"/migrations", "migrations"},
                {"/databases", "databases"}, {"/ai", "ai"}, {"/external-auth", "oa"},
                {"/alert-settings", "alerts"}, {"/change-logs", "change-logs"},
                {"/audits", "audits"}, {"/system-logs", "system-logs"},
                {"/system-config", "systemConfig"}, {"/settings", "settings"},
        };
        Matcher anchors = Pattern.compile("<a class=\"menu-item[\\s\\S]*?</a>").matcher(layout);
        int checked = 0;
        while (anchors.find()) {
            String block = anchors.group();
            for (String[] entry : entries) {
                if (block.contains("th:href=\"@{" + entry[0] + "}\"")) {
                    assertThat(block)
                            .as("menu entry %s must highlight when activeNav=%s",
                                    entry[0], entry[1])
                            .contains("th:classappend=\"${activeNav == '" + entry[1]
                                    + "'} ? 'is-active' : ''\"");
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(entries.length);
    }

    @Test
    void theActiveSubmenuHasAVisibleStyle() throws Exception {
        String css = Files.readString(
                Path.of("src/main/resources/static/css/app.css"), StandardCharsets.UTF_8);
        assertThat(css).contains(".nav-menu .menu-item.is-active {");
        assertThat(css).contains(".nav-menu .menu-item.is-active .ico { color: var(--brand); }");
    }
}
