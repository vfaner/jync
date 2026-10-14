package com.qqmu.jync.controller.api;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.service.upgrade.UpgradeService;
import com.qqmu.jync.service.upgrade.UpgradeService.Phase;
import com.qqmu.jync.service.upgrade.UpgradeService.Status;

/**
 * The JSON contract behind the one-click upgrade dialog.
 *
 * <p>app.js posts {@code source} from the mirror radio, polls {@code /status} for the
 * progress bar and phase transitions, and only enables the restart buttons once the phase
 * is READY. Refusals (upgrade already running, restart before download) must arrive as the
 * shared 409 body, not an HTML error page. Authorization lives in SecurityConfigTest; the
 * class signs in as ADMIN.
 */
@WebMvcTest(UpgradeApiController.class)
@WithMockUser(roles = "ADMIN")
class UpgradeApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private UpgradeService upgradeService;

    @Test
    void startPassesTheChosenSourceAndReturnsTheStartedKey() throws Exception {
        mvc.perform(post("/api/upgrade/start").param("source", "GITHUB").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("msg.upgrade.started"));
        verify(upgradeService).start("GITHUB");
    }

    @Test
    void startWithoutASourceDefaultsToTheGiteeMirror() throws Exception {
        mvc.perform(post("/api/upgrade/start").with(csrf()))
                .andExpect(status().isOk());
        verify(upgradeService).start("GITEE");
    }

    @Test
    void aRefusedStartBecomesA409CarryingTheReason() throws Exception {
        doThrow(new IllegalStateException("已有升级任务进行中，请等待其完成"))
                .when(upgradeService).start("GITEE");

        mvc.perform(post("/api/upgrade/start").param("source", "GITEE").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("已有升级任务进行中，请等待其完成"));
    }

    @Test
    void statusSerializesEveryFieldThePollerNeeds() throws Exception {
        when(upgradeService.status()).thenReturn(
                new Status(Phase.DOWNLOADING, null, "1.8.0", "GITEE", 123L, 456L));

        mvc.perform(get("/api/upgrade/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.phase").value("DOWNLOADING"))
                .andExpect(jsonPath("$.targetVersion").value("1.8.0"))
                .andExpect(jsonPath("$.source").value("GITEE"))
                .andExpect(jsonPath("$.bytesRead").value(123))
                .andExpect(jsonPath("$.bytesTotal").value(456));
    }

    @Test
    void restartRequestsTheRebootAndReturnsTheRestartingKey() throws Exception {
        mvc.perform(post("/api/upgrade/restart").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("msg.upgrade.restarting"));
        verify(upgradeService).restart();
    }

    @Test
    void restartBeforeAnUpgradeIsReadyIsA409() throws Exception {
        doThrow(new IllegalStateException("尚未完成升级下载，无法重启"))
                .when(upgradeService).restart();

        mvc.perform(post("/api/upgrade/restart").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("尚未完成升级下载，无法重启"));
    }
}
