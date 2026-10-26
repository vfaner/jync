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
import com.qqmu.jync.service.upgrade.UpgradeService.Localized;
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
        doThrow(new IllegalStateException("an upgrade is already running"))
                .when(upgradeService).start("GITEE");

        mvc.perform(post("/api/upgrade/start").param("source", "GITEE").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("an upgrade is already running"));
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
        doThrow(new IllegalStateException("nothing has been downloaded yet"))
                .when(upgradeService).restart();

        mvc.perform(post("/api/upgrade/restart").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("nothing has been downloaded yet"));
    }

    @Test
    void theStatusMessageIsRenderedInThePollersLocale() throws Exception {
        when(upgradeService.status()).thenReturn(new Status(Phase.READY,
                new Localized("upgrade.ready", "1.8.0"), "1.8.0", "GITEE", 10L, 10L));

        mvc.perform(get("/api/upgrade/status").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "Version 1.8.0 has been downloaded and swapped in; it takes effect"
                                + " after a restart"));

        mvc.perform(get("/api/upgrade/status").param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("新版本 1.8.0 已下载并替换完成，重启后生效"));
    }

    /**
     * What the operator actually reads when a mirror refuses anonymous calls: name the mirror,
     * say what 403 usually means, and tell them the way out. Both bundles, because app.js
     * prints this line verbatim.
     */
    @Test
    void aRateLimitedMirrorIsRenderedAsAdviceInBothLanguages() throws Exception {
        when(upgradeService.status()).thenReturn(new Status(Phase.FAILED,
                new Localized("upgrade.lookupFailed.rateLimited", "Gitee", 403),
                null, "GITEE", 0, 0));

        mvc.perform(get("/api/upgrade/status").param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "获取最新版本失败: Gitee 拒绝了匿名请求（HTTP 403，通常是匿名调用限流）"
                                + "。请切换下载源，或稍后重试"));

        mvc.perform(get("/api/upgrade/status").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "Could not fetch the latest version: Gitee refused the anonymous API"
                                + " request (HTTP 403, usually rate limiting). Choose the other"
                                + " download source or try again later"));
    }

    @Test
    void anEnglishPollNeverSeesChinese() throws Exception {
        // app.js renders this message verbatim -- it goes nowhere near the frontend dictionary --
        // so the server has to resolve it, in the poller's language, before it leaves.
        when(upgradeService.status()).thenReturn(new Status(Phase.FAILED,
                new Localized("upgrade.failed.sizeMismatch", "1024", "512"), null, "GITEE", 0, 0));

        mvc.perform(get("/api/upgrade/status").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "Downloaded file size mismatch: expected 1024 bytes, got 512"
                                + " (the transfer may have been truncated)"));
    }
}
