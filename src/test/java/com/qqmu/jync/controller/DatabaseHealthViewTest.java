package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The connections page grows a health dot per row. The column exists only for an admin: the
 * endpoint behind it dials the configured production databases, and the rest of that page's
 * dialing controls are admin-only for the same reason.
 */
@WebMvcTest(DatabaseConfigController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class DatabaseHealthViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DatabaseConfigService service;

    private DatabaseConfig config(long id, String name, ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setRole(role);
        config.setType(DatabaseType.MYSQL);
        config.setHost("127.0.0.1");
        config.setPort(3306);
        config.setDatabaseName("jyncdb");
        config.setUsername("root");
        config.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return config;
    }

    private String render(Locale locale, UserRole role) throws Exception {
        Page<DatabaseConfig> sources = new PageImpl<>(
                java.util.List.of(config(1L, "MySQL源", ConnectionRole.SOURCE)));
        Page<DatabaseConfig> targets = new PageImpl<>(
                java.util.List.of(config(2L, "H2目标", ConnectionRole.TARGET)));
        when(service.findPageByRole(any(), anyInt(), anyInt()))
                .thenReturn(sources).thenReturn(targets);

        return mvc.perform(get("/databases").locale(locale).with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void everyRowCarriesItsConnectionIdForTheHealthCall() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE, UserRole.ADMIN);

        assertThat(html).contains("data-health-id=\"1\"").contains("data-health-id=\"2\"");
        assertThat(html).contains("data-health-url=\"/api/databases/health\"");
    }

    @Test
    void rowsStartAsNotCheckedRatherThanPretendingToBeHealthy() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE, UserRole.ADMIN);

        assertThat(html).contains("未检测");
        assertThat(html).doesNotContain("健康");
    }

    @Test
    void eachSectionHasItsOwnRecheckButton() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE, UserRole.ADMIN);

        assertThat(count(html, "data-health-refresh")).isEqualTo(2);
        assertThat(html).contains("检测全部");
    }

    @Test
    void aViewerIsShownNoHealthColumnAndNoChecksAreStarted() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE, UserRole.VIEWER);

        assertThat(html).doesNotContain("data-health-id")
                .doesNotContain("data-health-url")
                .doesNotContain("data-health-refresh");
    }

    /**
     * The API answers with a sentinel key; the page swaps it for a label. Only the timeout
     * sentinel had a label, so a connection the driver rejected after checkout — {@code isValid}
     * returned false — showed the raw {@code error.db.health.invalid} in the tooltip.
     */
    @Test
    void theInvalidConnectionSentinelGetsALabelToo() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE, UserRole.ADMIN);
        String js = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/static/js/app.js"),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(html).as("模板要把文案交给前端").contains("data-invalid=\"连接已失效\"");
        assertThat(js).as("前端要把哨兵换成文案，否则 title 上挂的是 error.db.health.invalid")
                .contains("'error.db.health.invalid'");
    }

    @Test
    void englishRendersTheEnglishWording() throws Exception {
        String html = render(Locale.ENGLISH, UserRole.ADMIN);

        assertThat(html).contains("Not checked").contains("Check all").doesNotContain("未检测");
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return found;
            }
            found++;
            from = at + needle.length();
        }
    }

    private static RequestPostProcessor as(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role == UserRole.ADMIN ? "admin" : "view");
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
