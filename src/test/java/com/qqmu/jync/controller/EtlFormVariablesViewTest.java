package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.etl.EtlBatchService;
import com.qqmu.jync.service.etl.EtlPrecheckService;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * The variables box is one {@code name=value} per line. It has to show the value it was saved
 * with, name every built-in the operator can use without defining anything, and appear for a
 * Mongo task too — pre- and post-SQL are not SQL-source-only.
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlFormVariablesViewTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EtlTaskService etlTaskService;

    @MockitoBean
    private EtlPrecheckService precheckService;

    @MockitoBean
    private EtlBatchService etlBatchService;

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

    private String html(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return action.andReturn().getResponse().getContentAsString();
    }

    /** The rendered {@code <textarea>} element carrying the given id, closing tag included. */
    private static String textarea(String page, String id) {
        int at = page.indexOf("id=\"" + id + "\"");
        assertThat(at).as("textarea %s rendered", id).isPositive();
        int start = page.lastIndexOf("<textarea", at);
        assertThat(start).as("textarea %s opening tag", id).isNotNegative();
        return page.substring(start, page.indexOf("</textarea>", at) + "</textarea>".length());
    }

    @Test
    void theFormOffersTheVariablesBoxAndNamesTheBuiltIns() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(textarea(page, "variables")).contains("name=\"variables\"");
        assertThat(page).contains("one name=value per line");
        // Every built-in the operator can write without defining it.
        assertThat(page).contains("${date}", "${datetime}", "${yesterday}", "${month}",
                "${year}", "${taskName}", "${targetTable}");
    }

    /** Read order: define the values first, then write the query that references them. */
    @Test
    void theBoxSitsAboveTheExtractionSqlItAnswers() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(page.indexOf("id=\"variables\""))
                .as("variables box rendered before the extraction SQL")
                .isBetween(0, page.indexOf("id=\"sourceSql\"") - 1);
    }

    @Test
    void theSavedDefinitionsAreShownAgain() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL),
                connection(2L, "mysql-dst", DatabaseType.MYSQL)));
        when(etlTaskService.findById(5L)).thenReturn(Optional.of(task(5L)));

        String page = html(mvc.perform(get("/etl/5/edit").with(asAdmin()))
                .andExpect(status().isOk()));

        // The browser un-escapes the quote when the textarea is submitted; the page must not
        // print it raw, or a value containing markup would become markup.
        assertThat(textarea(page, "variables"))
                .contains("tenant=&#39;acme&#39;", "batch=nightly");
    }

    @Test
    void aMongoTaskSeesTheVariablesBoxToo() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mongo-src", DatabaseType.MONGODB_NATIVE),
                connection(2L, "mysql-dst", DatabaseType.MYSQL)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(textarea(page, "variables")).contains("name=\"variables\"");
    }

    // --- helpers ------------------------------------------------------------------------

    private static EtlTask task(Long id) {
        EtlTask task = new EtlTask();
        task.setId(id);
        task.setName("nightly orders");
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setSourceSql("SELECT id FROM src");
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setVariables("tenant='acme'\nbatch=nightly");
        return task;
    }

    private static DatabaseConfig connection(long id, String name, DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setType(type);
        config.setRole(ConnectionRole.TARGET);
        return config;
    }
}
