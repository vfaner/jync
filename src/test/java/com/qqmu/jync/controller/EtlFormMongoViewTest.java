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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
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
 * The ETL form serves two kinds of source, so the SQL field cannot be HTML-required: a native
 * Mongo task has no SQL at all, and the browser would refuse to submit it. Both field groups
 * render on every page; the server validates whichever group the chosen source type needs.
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlFormMongoViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EtlTaskService etlTaskService;

    @MockBean
    private EtlPrecheckService precheckService;

    @MockBean
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

    private static DatabaseConfig connection(long id, String name, DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setType(type);
        return config;
    }

    private static DatabaseConfig connection(long id, String name, DatabaseType type,
                                             ConnectionRole role) {
        DatabaseConfig config = connection(id, name, type);
        config.setRole(role);
        return config;
    }

    private String html(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return action.andReturn().getResponse().getContentAsString();
    }

    /** The rendered element starting at {@code <textarea} and ending at {@code </textarea>}. */
    private static String textarea(String page, String id) {
        int at = page.indexOf("id=\"" + id + "\"");
        assertThat(at).as("textarea %s rendered", id).isPositive();
        int start = page.lastIndexOf("<textarea", at);
        assertThat(start).as("textarea %s opening tag", id).isNotNegative();
        return page.substring(start, page.indexOf("</textarea>", at) + "</textarea>".length());
    }

    /** The rendered {@code <input>} tag carrying the given id. */
    private static String input(String page, String id) {
        int at = page.indexOf("id=\"" + id + "\"");
        assertThat(at).as("input %s rendered", id).isPositive();
        int start = page.lastIndexOf("<input", at);
        assertThat(start).as("input %s opening tag", id).isNotNegative();
        return page.substring(start, page.indexOf('>', at) + 1);
    }

    /** The rendered {@code <select>} element with the given id, options included. */
    private static String select(String page, String id) {
        int at = page.indexOf("id=\"" + id + "\"");
        assertThat(at).as("select %s rendered", id).isPositive();
        int start = page.lastIndexOf("<select", at);
        return page.substring(start, page.indexOf("</select>", at));
    }

    @Test
    void bothTheSqlAndTheMongoSourceFieldsRender() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL),
                connection(2L, "mongo-src", DatabaseType.MONGODB_NATIVE)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(textarea(page, "sourceSql")).isNotEmpty();
        assertThat(input(page, "sourceCollection"))
                .contains("name=\"sourceCollection\"", "maxlength=\"255\"");
        assertThat(textarea(page, "sourceFilter"))
                .contains("name=\"sourceFilter\"", "maxlength=\"4000\"", "placeholder=\"{}\"");
    }

    @Test
    void theSqlTextareaIsNotHtmlRequiredBecauseMongoTasksHaveNoSql() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(textarea(page, "sourceSql"))
                .contains("name=\"sourceSql\"")
                .doesNotContain("required");
    }

    @Test
    void aNativeMongoConnectionIsOfferedAsASourceButNeverAsATarget() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-dst", DatabaseType.MYSQL, ConnectionRole.TARGET),
                connection(2L, "mongo-src", DatabaseType.MONGODB_NATIVE, ConnectionRole.SOURCE)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(select(page, "sourceDbId")).contains("mongo-src");
        assertThat(select(page, "targetDbId"))
                .contains("mysql-dst")
                .doesNotContain("mongo-src");
    }

    @Test
    void theEditPageKeepsTheSavedCollectionAndFilter() throws Exception {
        EtlTask task = new EtlTask();
        task.setId(7L);
        task.setName("mongo etl");
        task.setSourceDbId(2L);
        task.setTargetDbId(1L);
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setSourceSql("");
        task.setSourceCollection("orders");
        task.setSourceFilter("{\"status\":\"paid\"}");
        when(etlTaskService.findById(7L)).thenReturn(Optional.of(task));
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL),
                connection(2L, "mongo-src", DatabaseType.MONGODB_NATIVE)));

        String page = html(mvc.perform(get("/etl/7/edit").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(input(page, "sourceCollection")).contains("value=\"orders\"");
        // Quotes are HTML-escaped in the rendered text node, so assert on the content.
        assertThat(textarea(page, "sourceFilter"))
                .contains("status")
                .contains("paid");
    }
}
