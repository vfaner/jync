package com.qqmu.jync.controller.api;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.service.projection.redis.RedisRebuildService;

/**
 * The JSON contract behind the Redis card's rebuild button. The button's toast resolves
 * {@code message} as a bundle key; the service's refusals become the shared 409 body.
 */
@WebMvcTest(RedisRebuildApiController.class)
@WithMockUser(roles = "ADMIN")
class RedisRebuildApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private RedisRebuildService service;

    @Test
    void rebuildReturnsTheCountsTheButtonReports() throws Exception {
        when(service.rebuild(1L, true))
                .thenReturn(new RedisRebuildService.RebuildResult(2, 7, 4, 123));

        mvc.perform(post("/api/projects/1/redis-rebuild").param("sweep", "true").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("msg.redis.rebuild.done"))
                .andExpect(jsonPath("$.tables").value(2))
                .andExpect(jsonPath("$.rows").value(7))
                .andExpect(jsonPath("$.sweptKeys").value(4))
                .andExpect(jsonPath("$.elapsedMs").value(123));
    }

    @Test
    void sweepDefaultsToOffWhenTheParameterIsAbsent() throws Exception {
        when(service.rebuild(1L, false))
                .thenReturn(new RedisRebuildService.RebuildResult(0, 0, 0, 1));

        mvc.perform(post("/api/projects/1/redis-rebuild").with(csrf()))
                .andExpect(status().isOk());

        verify(service).rebuild(1L, false);
    }

    @Test
    void aRunningProjectIsRefusedWithTheSharedConflictBody() throws Exception {
        doThrow(new IllegalStateException("error.project.redis.rebuild.mustStop"))
                .when(service).rebuild(1L, false);

        mvc.perform(post("/api/projects/1/redis-rebuild").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.project.redis.rebuild.mustStop"));
    }

    // A viewer is refused by the blanket POST rule; that scan lives in SecurityConfigTest
    // (everyWriteEndpointInTheAppRefusesAViewer), which discovers this handler by itself.
}
