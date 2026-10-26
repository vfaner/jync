package com.qqmu.jync.controller.api;

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

import com.qqmu.jync.service.version.VersionService;
import com.qqmu.jync.service.version.VersionService.UpdateCheck;

/**
 * The JSON contract behind the settings page's nav badge. The check itself — Gitee first,
 * GitHub second, silent when both fail — lives in {@link VersionService#checkNow()}; here
 * it is stubbed. Any authenticated user may ask: the settings page is readable by viewers.
 */
@WebMvcTest(VersionApiController.class)
@WithMockUser(roles = "VIEWER")
class VersionApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private VersionService versionService;

    @Test
    void checkSerializesEverythingTheBadgeNeeds() throws Exception {
        when(versionService.checkNow()).thenReturn(new UpdateCheck(true, true, "1.7.6",
                "1.7.7", "v1.7.7",
                "https://gitee.com/super_rgh/jync/releases/tag/v1.7.7", false));

        mvc.perform(get("/api/version/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.checked").value(true))
                .andExpect(jsonPath("$.updateAvailable").value(true))
                .andExpect(jsonPath("$.artifactUpdated").value(false))
                .andExpect(jsonPath("$.currentVersion").value("1.7.6"))
                .andExpect(jsonPath("$.latestVersion").value("1.7.7"))
                .andExpect(jsonPath("$.latestTag").value("v1.7.7"))
                .andExpect(jsonPath("$.releaseUrl")
                        .value("https://gitee.com/super_rgh/jync/releases/tag/v1.7.7"));
    }

    /**
     * Same version, new bytes: the badge must still light up, and the flag lets app.js word it
     * as "artifact updated" instead of announcing a "new version" that equals the running one.
     */
    @Test
    void aRePublishedArtifactIsFlaggedSeparatelyFromANewVersion() throws Exception {
        when(versionService.checkNow()).thenReturn(new UpdateCheck(true, true, "1.8.0",
                "1.8.0", "v1.8.0",
                "https://github.com/vfaner/jync/releases/tag/v1.8.0", true));

        mvc.perform(get("/api/version/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateAvailable").value(true))
                .andExpect(jsonPath("$.artifactUpdated").value(true));
    }

    /**
     * Dismissing the "package re-published" notice is a POST (therefore admin-only almost
     * everywhere in this app) and answers with whether anything was actually recorded, so the
     * page can tell a real dismissal from "there was nothing to dismiss".
     */
    @Test
    void dismissingARePublishedArtifactIsRecorded() throws Exception {
        when(versionService.dismissArtifact()).thenReturn(true);

        mvc.perform(post("/api/version/artifact/dismiss").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.dismissed").value(true));
        verify(versionService).dismissArtifact();
    }

    @Test
    void dismissingWithNothingPublishedStillAnswersQuietly() throws Exception {
        when(versionService.dismissArtifact()).thenReturn(false);

        mvc.perform(post("/api/version/artifact/dismiss").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.dismissed").value(false));
    }

    @Test
    void aSilentCheckIsStill200WithNothingToShow() throws Exception {
        when(versionService.checkNow())
                .thenReturn(new UpdateCheck(false, false, "1.7.6", null, null, null, false));

        mvc.perform(get("/api/version/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.checked").value(false))
                .andExpect(jsonPath("$.updateAvailable").value(false))
                .andExpect(jsonPath("$.artifactUpdated").value(false));
    }
}
