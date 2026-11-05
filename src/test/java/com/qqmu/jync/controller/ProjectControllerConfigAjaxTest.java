package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisTargetService;

/**
 * The detail page saves its configuration in the background: a full page reload re-runs
 * {@code listSourceObjects} against the live source (thousands of tables on a large schema)
 * and throws away the scroll position, for a save that changed nothing on the page.
 *
 * <p>The AJAX mapping is selected by the {@code X-Jync-Ajax} header and answers JSON; the
 * plain form POST must keep returning the PRG redirect with a flash message. These tests
 * pin both paths, including the two things the page cannot infer locally: why start/sync-now
 * became unusable (an empty selection) and when it must re-render after all (a strategy
 * switch moves the CDC panel on or off the page).
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class ProjectControllerConfigAjaxTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;
    @MockBean private RedisTargetService redisTargetService;

    /** Stubs a stopped project whose stored config is the instance the controller mutates. */
    private SyncConfig stubStoppedProject() {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);
        when(projectService.loadConfig(project)).thenReturn(config);
        return config;
    }

    @Test
    void ajaxSaveAnswersJsonInsteadOfRedirecting() throws Exception {
        stubStoppedProject();

        mvc.perform(post("/projects/1/config").with(csrf())
                        .header("X-Jync-Ajax", "1")
                        .param("objectSelectionExplicit", "true")
                        .param("tables", "orders")
                        .param("syncStructure", "true")
                        .param("syncData", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.reload").value(false))
                // Localized server-side, like the flash banner: the raw key must never
                // reach the page.
                .andExpect(jsonPath("$.message").value(not("msg.config.saved")))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void ajaxSaveOfAnEmptySelectionReturnsTheNoWorkReason() throws Exception {
        stubStoppedProject();

        mvc.perform(post("/projects/1/config").with(csrf())
                        .header("X-Jync-Ajax", "1")
                        .param("objectSelectionExplicit", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.noSyncWork").isNotEmpty());
    }

    @Test
    void ajaxSaveReportsAValidationFailureAsLocalizedJsonBadRequest() throws Exception {
        stubStoppedProject();
        doThrow(new IllegalArgumentException("error.project.cron.invalid"))
                .when(projectService).saveConfig(eq(1L), any(SyncConfig.class), isNull());

        mvc.perform(post("/projects/1/config").with(csrf())
                        .header("X-Jync-Ajax", "1")
                        .param("objectSelectionExplicit", "true")
                        .param("tables", "orders")
                        .param("syncStructure", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(not("error.project.cron.invalid")))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void ajaxStrategySwitchAsksThePageToReload() throws Exception {
        stubStoppedProject();

        mvc.perform(post("/projects/1/config").with(csrf())
                        .header("X-Jync-Ajax", "1")
                        .param("objectSelectionExplicit", "true")
                        .param("tables", "orders")
                        .param("syncStructure", "true")
                        .param("captureStrategy", "BINLOG_CDC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.reload").value(true));
    }

    @Test
    void aPlainFormPostKeepsTheRedirectAndItsFlashMessage() throws Exception {
        stubStoppedProject();

        MvcResult result = mvc.perform(post("/projects/1/config").with(csrf())
                        .param("objectSelectionExplicit", "true")
                        .param("tables", "orders")
                        .param("syncStructure", "true"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getFlashMap().get("message")).isEqualTo("msg.config.saved");
        assertThat(result.getFlashMap().get("error")).isNull();
    }
}
