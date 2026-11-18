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
 * The write mode decides which of the three option fields can take effect: the range condition
 * only bounds a range overwrite, the resume key only continues an append or a range overwrite,
 * and the upsert key only names the match of an upsert. The form renders only the applicable
 * ones on the first paint; app.js follows the select after that.
 *
 * <p>The hidden fields stay in the form: switching modes back and forth must not silently drop
 * what the operator typed, and the server still judges the final mode's requirements.
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlFormWriteModeViewTest {

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

    /** The opening tag of the field group carrying the given data-role. */
    private static String group(String page, String role) {
        int at = page.indexOf("data-role=\"" + role + "\"");
        assertThat(at).as("field group %s rendered", role).isPositive();
        return page.substring(page.lastIndexOf("<div", at), page.indexOf('>', at) + 1);
    }

    private static boolean hidden(String page, String role) {
        return group(page, role).contains("hidden");
    }

    private static EtlTask task(EtlWriteMode mode) {
        EtlTask task = new EtlTask();
        task.setId(9L);
        task.setName("nightly");
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setSourceSql("SELECT id FROM src");
        task.setTargetTable("dst");
        task.setWriteMode(mode);
        return task;
    }

    private static DatabaseConfig connection(long id, String name, ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setType(DatabaseType.MYSQL);
        config.setRole(role);
        return config;
    }

    private void stubConnections() {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", ConnectionRole.SOURCE),
                connection(2L, "mysql-dst", ConnectionRole.TARGET)));
    }

    private String editPage(EtlWriteMode mode) throws Exception {
        stubConnections();
        when(etlTaskService.findById(9L)).thenReturn(Optional.of(task(mode)));
        return html(mvc.perform(get("/etl/9/edit").with(asAdmin()))
                .andExpect(status().isOk()));
    }

    /** Overwrite uses none of them, and the fields stay in the form for the next mode. */
    @Test
    void overwriteHidesAllThreeModeSpecificFields() throws Exception {
        stubConnections();

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(hidden(page, "range-group")).isTrue();
        assertThat(hidden(page, "resume-group")).isTrue();
        assertThat(hidden(page, "upsert-group")).isTrue();
        assertThat(page).contains("name=\"rangeCondition\"", "name=\"resumeKeyColumn\"",
                "name=\"upsertKeyColumns\"");
    }

    @Test
    void rangeOverwriteShowsTheRangeConditionAndTheResumeKey() throws Exception {
        String page = editPage(EtlWriteMode.RANGE_OVERWRITE);

        assertThat(hidden(page, "range-group")).isFalse();
        assertThat(hidden(page, "resume-group")).isFalse();
        assertThat(hidden(page, "upsert-group")).isTrue();
    }

    @Test
    void appendShowsOnlyTheResumeKey() throws Exception {
        String page = editPage(EtlWriteMode.APPEND);

        assertThat(hidden(page, "resume-group")).isFalse();
        assertThat(hidden(page, "range-group")).isTrue();
        assertThat(hidden(page, "upsert-group")).isTrue();
    }

    @Test
    void upsertShowsOnlyTheUpsertKey() throws Exception {
        String page = editPage(EtlWriteMode.UPSERT);

        assertThat(hidden(page, "upsert-group")).isFalse();
        assertThat(hidden(page, "range-group")).isTrue();
        assertThat(hidden(page, "resume-group")).isTrue();
    }
}
