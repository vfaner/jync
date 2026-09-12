package com.synctool.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.synctool.model.AppUser;
import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.model.UserRole;
import com.synctool.service.DatabaseConfigService;
import com.synctool.service.auth.SyncUserDetails;

/**
 * Server-side first-paint state of the driver-jar card on the connection form.
 *
 * <p>The card must already be hidden (or shown) in the served HTML; waiting for the
 * type-defaults AJAX call would flash the card on every GBase / Oscar edit page. The rule is
 * classpath-driven through {@code DriverPresence}: bundled presets hide it, GBase and Oscar
 * show it, and CUSTOM always shows it.
 */
@WebMvcTest(DatabaseConfigController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class DatabaseConfigFormViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DatabaseConfigService service;

    /** Matches the jar-card element while it carries the hidden attribute. */
    private static final Pattern JAR_CARD_HIDDEN =
            Pattern.compile("id=\"jar-card\"[^>]*hidden", Pattern.DOTALL);

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

    private String html(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return action.andReturn().getResponse().getContentAsString();
    }

    @Test
    void newFormDefaultsToMysqlAndHidesTheJarCard() throws Exception {
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(JAR_CARD_HIDDEN.matcher(page).find())
                .as("MySQL is bundled, jar card must be hidden on first paint")
                .isTrue();
        assertThat(page).doesNotContain("??db.jarCard", "??db.jarCardHint");
        // hidden alone still submits the (blank or stale) value; the control must be disabled too
        assertThat(page).containsPattern("id=\"customJarPath\"[^>]*disabled");
    }

    @Test
    void newFormMarksThePrefilledPortAsAutofilled() throws Exception {
        // Without the flag app.js treats the server-prefilled 3306 as a user value and never
        // replaces it when the type changes.
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(page).containsPattern(
                "id=\"port\"[^>]*value=\"3306\"[^>]*data-autofilled=\"true\"|"
                + "id=\"port\"[^>]*data-autofilled=\"true\"[^>]*value=\"3306\"");
    }

    @Test
    void editFormDoesNotMarkTheStoredPortAsAutofilled() throws Exception {
        DatabaseConfig dm = new DatabaseConfig();
        dm.setId(9L);
        dm.setName("dm-prod");
        dm.setType(DatabaseType.DM);
        dm.setPort(5236);
        when(service.findById(9L)).thenReturn(Optional.of(dm));

        String page = html(mvc.perform(get("/databases/9/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(page).contains("id=\"port\"");
        assertThat(page).doesNotContain("data-autofilled");
    }

    @Test
    void editingAGbaseConnectionShowsTheJarCard() throws Exception {
        DatabaseConfig gbase = new DatabaseConfig();
        gbase.setId(7L);
        gbase.setName("gbase-prod");
        gbase.setType(DatabaseType.GBASE);
        when(service.findById(7L)).thenReturn(Optional.of(gbase));

        String page = html(mvc.perform(get("/databases/7/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(JAR_CARD_HIDDEN.matcher(page).find())
                .as("GBase has no bundled driver, jar card must be visible on first paint")
                .isFalse();
        assertThat(page).contains("id=\"jar-card\"");
        // The path must actually submit for an external-driver type.
        assertThat(page).doesNotContainPattern(
                java.util.regex.Pattern.compile("id=\"customJarPath\"[^>]*disabled"));
    }

    @Test
    void editingADamengConnectionHidesTheJarCard() throws Exception {
        DatabaseConfig dm = new DatabaseConfig();
        dm.setId(8L);
        dm.setName("dm-prod");
        dm.setType(DatabaseType.DM);
        when(service.findById(8L)).thenReturn(Optional.of(dm));

        String page = html(mvc.perform(get("/databases/8/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(JAR_CARD_HIDDEN.matcher(page).find())
                .as("DM is bundled now, jar card must stay hidden")
                .isTrue();
    }
}
