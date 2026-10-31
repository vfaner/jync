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
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.etl.EtlPrecheckService;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * The ETL form's two connection selects do not offer the same list.
 *
 * <p>Elasticsearch and MongoDB answer {@code SELECT} only, so a task that names one of them
 * as its target saves cleanly and dies on the first write. The server refuses that save, but
 * the operator should never be offered the choice in the first place.
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlFormTargetOptionsViewTest {

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
    void readOnlyConnectionsAreNotOfferedAsTargetsButRemainSources() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(
                connection(1L, "mysql-src", DatabaseType.MYSQL),
                connection(2L, "mongo-src", DatabaseType.MONGODB),
                connection(3L, "es-src", DatabaseType.ELASTICSEARCH)));

        String page = html(mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(select(page, "sourceDbId"))
                .contains("mysql-src", "mongo-src", "es-src");
        assertThat(select(page, "targetDbId"))
                .contains("mysql-src")
                .doesNotContain("mongo-src", "es-src");
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
                connection(1L, "mysql-src", DatabaseType.MYSQL),
                connection(2L, "mongo-src", DatabaseType.MONGODB)));

        String page = html(mvc.perform(get("/etl/5/edit").with(asAdmin()))
                .andExpect(status().isOk()));

        assertThat(select(page, "targetDbId"))
                .contains("mysql-src", "mongo-src")
                .containsPattern("value=\"2\"[^>]*selected");
    }
}
