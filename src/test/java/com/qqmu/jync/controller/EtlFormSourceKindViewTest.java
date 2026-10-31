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
import com.qqmu.jync.service.etl.EtlPrecheckService;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * The ETL form shows the fields the chosen source actually uses.
 *
 * <p>A native MongoDB source is read through a collection and an optional filter and has no
 * extraction SQL; every other source is SQL and has no collection. Rendering both halves at
 * once asked the operator to guess which boxes matter — the Mongo fields for a MySQL source
 * are ignored, and the SQL box for a Mongo source is. The form therefore splits them into two
 * groups, server-rendered from the saved task so an edit page never starts on the wrong half,
 * and <em>app.js</em> toggles them when the source select changes.
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlFormSourceKindViewTest {

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

    private static DatabaseConfig connection(long id, String name, DatabaseType type,
                                             ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setType(type);
        config.setRole(role);
        return config;
    }

    /** The rendered {@code <select>} element with the given id, options included. */
    private static String select(String page, String selectId) {
        int at = page.indexOf("id=\"" + selectId + "\"");
        assertThat(at).as("select %s rendered", selectId).isPositive();
        int start = page.lastIndexOf("<select", at);
        int end = page.indexOf("</select>", at);
        return page.substring(start, end);
    }

    /** The opening tag that carries the marker, so a per-element attribute can be asserted. */
    private static String startTag(String page, String marker) {
        int at = page.indexOf(marker);
        assertThat(at).as("element %s rendered", marker).isPositive();
        int start = page.lastIndexOf('<', at);
        int end = page.indexOf('>', at);
        return page.substring(start, end + 1);
    }

    private String html(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return action.andReturn().getResponse().getContentAsString();
    }

    @Test
    void aNewFormTagsEachSourceOptionAndStartsWithTheSqlBox() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL, ConnectionRole.SOURCE),
                connection(2L, "mongo-src", DatabaseType.MONGODB_NATIVE, ConnectionRole.SOURCE),
                connection(3L, "mysql-dst", DatabaseType.MYSQL, ConnectionRole.TARGET)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        // The browser can only toggle on a type it can read off the option.
        assertThat(select(page, "sourceDbId"))
                .contains("data-type=\"MYSQL\"", "data-type=\"MONGODB_NATIVE\"");

        // Nothing is chosen yet, and every not-yet-chosen source is the SQL kind.
        assertThat(startTag(page, "data-role=\"source-sql-group\""))
                .doesNotContain("hidden");
        assertThat(startTag(page, "data-role=\"source-mongo-group\""))
                .contains("hidden");
    }

    /**
     * An edit page must render the half the task actually uses. If it started on the SQL side,
     * a Mongo operator would see an empty extraction box and no collection until app.js ran.
     */
    @Test
    void editingANativeMongoTaskStartsWithTheMongoFieldsShownAndTheSqlBoxHidden() throws Exception {
        EtlTask task = new EtlTask();
        task.setId(5L);
        task.setName("students-to-mysql");
        task.setSourceDbId(2L);
        task.setTargetDbId(1L);
        task.setSourceCollection("students");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        when(etlTaskService.findById(5L)).thenReturn(Optional.of(task));
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-dst", DatabaseType.MYSQL, ConnectionRole.TARGET),
                connection(2L, "mongo-src", DatabaseType.MONGODB_NATIVE, ConnectionRole.SOURCE)));

        String page = html(mvc.perform(get("/etl/5/edit").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(startTag(page, "data-role=\"source-mongo-group\""))
                .doesNotContain("hidden");
        assertThat(startTag(page, "data-role=\"source-sql-group\""))
                .contains("hidden");
    }
}
