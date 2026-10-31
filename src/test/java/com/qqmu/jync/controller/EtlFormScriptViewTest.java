package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.etl.EtlPrecheckService;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * The extraction box accepts a script, and the operator can load one from a .sql file. The
 * file is read in the browser into the textarea; it must not become a submitted form field,
 * because the form is a plain urlencoded POST that the controller never parses as multipart.
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlFormScriptViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EtlTaskService etlTaskService;

    @MockBean
    private EtlPrecheckService precheckService;

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

    private static DatabaseConfig connection(long id, String name, DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setType(type);
        return config;
    }

    private String html(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return action.andReturn().getResponse().getContentAsString();
    }

    /** The rendered {@code <textarea>} tag carrying the given id. */
    private static String textarea(String page, String id) {
        int at = page.indexOf("id=\"" + id + "\"");
        assertThat(at).as("textarea %s rendered", id).isPositive();
        int start = page.lastIndexOf("<textarea", at);
        assertThat(start).as("textarea %s opening tag", id).isNotNegative();
        return page.substring(start, page.indexOf('>', at) + 1);
    }

    /** The rendered {@code <input>} tag carrying the given id. */
    private static String input(String page, String id) {
        int at = page.indexOf("id=\"" + id + "\"");
        assertThat(at).as("input %s rendered", id).isPositive();
        int start = page.lastIndexOf("<input", at);
        assertThat(start).as("input %s opening tag", id).isNotNegative();
        return page.substring(start, page.indexOf('>', at) + 1);
    }

    @Test
    void theExtractionBoxOffersLoadingAScriptFile() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(input(page, "sourceSqlFile"))
                .contains("type=\"file\"", "accept=");
        assertThat(page).contains("for=\"sourceSqlFile\"");
    }

    @Test
    void theSqlBoxesAreHighlightedLikeTheConversionEditor() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        // The editable highlighter takes a transparent textarea over a colored layer; wrap=off
        // keeps both at the same character grid.
        assertThat(textarea(page, "sourceSql"))
                .contains("sql-edit", "wrap=\"off\"", "spellcheck=\"false\"");
        assertThat(textarea(page, "preSql")).contains("sql-edit");
        assertThat(textarea(page, "postSql")).contains("sql-edit");
        // The MongoDB filter is JSON, not SQL: the SQL tokenizer would only mis-color it.
        assertThat(textarea(page, "sourceFilter")).doesNotContain("sql-edit");
        assertThat(page).contains("/js/sql-highlight.js");
    }

    @Test
    void theFileInputIsNeverASubmittedFormField() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(input(page, "sourceSqlFile")).doesNotContain("name=");
    }
}
