package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * The ETL form's two connection selects do not offer the same list.
 *
 * <p>The split has two rules. First, only connections classified for the role appear: sources
 * are read-only by intent, targets writable. Second, Elasticsearch and MongoDB answer
 * {@code SELECT} only, so a task that names one of them as its target saves cleanly and dies
 * on the first write. The server refuses that save, but the operator should never be offered
 * the choice in the first place.
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlFormTargetOptionsViewTest {

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

    private String html(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return action.andReturn().getResponse().getContentAsString();
    }

    @Test
    void selectorsAreFilteredByRoleAndReadOnlyConnectionsAreNeverTargets() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL, ConnectionRole.SOURCE),
                connection(2L, "mongo-src", DatabaseType.MONGODB, ConnectionRole.SOURCE),
                connection(3L, "es-src", DatabaseType.ELASTICSEARCH, ConnectionRole.SOURCE),
                connection(4L, "mysql-dst", DatabaseType.MYSQL, ConnectionRole.TARGET)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(select(page, "sourceDbId"))
                .contains("mysql-src", "mongo-src", "es-src")
                .doesNotContain("mysql-dst");
        assertThat(select(page, "targetDbId"))
                .contains("mysql-dst")
                .doesNotContain("mysql-src", "mongo-src", "es-src");
    }

    /**
     * A task saved before this restriction still points at a read-only target. The edit page
     * must render that value so the operator can see what is wrong and pick another one,
     * instead of the select silently falling back to the first writable connection.
     */
    @Test
    void anExistingReadOnlyTargetStillRendersOnTheEditPage() throws Exception {
        EtlTask task = new EtlTask();
        task.setId(5L);
        task.setName("legacy");
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        when(etlTaskService.findById(5L)).thenReturn(Optional.of(task));
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL, ConnectionRole.SOURCE),
                connection(2L, "mongo-src", DatabaseType.MONGODB, ConnectionRole.SOURCE)));

        String page = html(mvc.perform(get("/etl/5/edit").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(select(page, "targetDbId"))
                .contains("mongo-src")
                .doesNotContain("mysql-src")
                .containsPattern("value=\"2\"[^>]*selected");
    }

    /**
     * The target table is typed free-hand, but the form also offers what the chosen target
     * connection actually contains: the field publishes the listing endpoint and the status
     * copy, and carries a listbox element for the picker app.js fills in.
     */
    @Test
    void theTargetTableFieldPublishesTheListingEndpointAndItsStatusCopy() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-dst", DatabaseType.MYSQL, ConnectionRole.TARGET)));

        String page = html(mvc.perform(get("/etl/new")
                        .locale(Locale.SIMPLIFIED_CHINESE).with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(page).contains("data-role=\"target-table\"")
                .contains("data-role=\"target-table-panel\"")
                .contains("data-tables-url=\"/api/databases\"")
                .contains("data-loading-text=\"正在读取目标表…\"")
                .contains("data-loaded-text=\"已读取 {0} 张表，可输入关键字筛选\"")
                .contains("data-failed-text=\"目标表读取失败，可手动输入表名\"")
                .contains("data-empty-text=\"该连接下没有表\"")
                .contains("data-no-match-text=\"没有匹配的表\"");
    }

    /**
     * The same legacy value must stay visible when it is the source: a connection reclassified
     * to TARGET after the task was saved would otherwise vanish from the edit page.
     */
    @Test
    void anExistingSourceWhoseRoleNoLongerMatchesStillRendersOnTheEditPage() throws Exception {
        EtlTask task = new EtlTask();
        task.setId(6L);
        task.setName("legacy-pair");
        task.setSourceDbId(2L);
        task.setTargetDbId(1L);
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        when(etlTaskService.findById(6L)).thenReturn(Optional.of(task));
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-dst", DatabaseType.MYSQL, ConnectionRole.TARGET),
                connection(2L, "misfiled", DatabaseType.MYSQL, ConnectionRole.TARGET)));

        String page = html(mvc.perform(get("/etl/6/edit").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(select(page, "sourceDbId"))
                .contains("misfiled")
                .containsPattern("value=\"2\"[^>]*selected")
                .doesNotContain("mysql-dst");
    }
}
