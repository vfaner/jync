package com.qqmu.jync.controller.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.service.ai.AiProviderService;
import com.qqmu.jync.service.ai.support.SupportAssistantService;
import com.qqmu.jync.service.ai.support.SupportSink;

@WebMvcTest(SupportApiController.class)
@WithMockUser(username = "admin", roles = "ADMIN")
class SupportApiControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private SupportAssistantService assistant;
    @MockitoBean
    private AiProviderService providers;

    @Test
    void aiOffIsA200StreamWithAnErrorEventNotA403() throws Exception {
        when(providers.isAssistAvailable()).thenReturn(false);

        MvcResult result = mvc.perform(post("/api/support/chat").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"history\":[]}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getAsyncResult(3000)).isNull();
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("event:error")
                .contains("error.ai.disabled")
                .contains("event:done");
    }

    @Test
    void aBusyUserGets409() throws Exception {
        when(providers.isAssistAvailable()).thenReturn(true);
        when(providers.activeProvider()).thenReturn(Optional.of(new AiProvider()));
        when(assistant.tryStart("admin")).thenReturn(false);

        mvc.perform(post("/api/support/chat").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"history\":[]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aNormalAnswerIsStreamedAndAlwaysEndsWithDone() throws Exception {
        when(providers.isAssistAvailable()).thenReturn(true);
        when(providers.activeProvider()).thenReturn(Optional.of(new AiProvider()));
        when(assistant.tryStart("admin")).thenReturn(true);
        Mockito.doAnswer(invocation -> {
            SupportSink sink = invocation.getArgument(3);
            sink.delta("它在跑");
            return null;
        }).when(assistant).ask(any(), any(), any(), any());

        MvcResult result = mvc.perform(post("/api/support/chat").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"在跑吗\",\"history\":[]}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getAsyncResult(3000)).isNull();
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("event:delta")
                .contains("它在跑")
                .contains("event:done");
        Mockito.verify(assistant).finish("admin");
    }
}
