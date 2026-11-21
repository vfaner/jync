package com.qqmu.jync.controller.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.service.ai.AiProviderService;
import com.qqmu.jync.service.ai.support.SupportActionService;
import com.qqmu.jync.service.ai.support.SupportAssistantService;
import com.qqmu.jync.service.ai.support.SupportRunStore;
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
    @MockitoBean
    private SupportActionService actions;
    @MockitoBean
    private SupportRunStore runs;

    @Test
    void aiOffIsA200StreamWithAnErrorEventNotA403() throws Exception {
        // activeProvider() is empty by default on the mock: no provider, no tools, no call.
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
        when(providers.activeProvider()).thenReturn(Optional.of(new AiProvider()));
        when(assistant.tryStart("admin")).thenReturn(false);

        mvc.perform(post("/api/support/chat").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"history\":[]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aNormalAnswerIsStreamedAndAlwaysEndsWithDone() throws Exception {
        when(providers.activeProvider()).thenReturn(Optional.of(new AiProvider()));
        when(assistant.tryStart("admin")).thenReturn(true);
        when(runs.create("admin")).thenReturn("run-1");
        Mockito.doAnswer(invocation -> {
            SupportSink sink = invocation.getArgument(3);
            sink.delta("它在跑");
            return null;
        }).when(assistant).ask(any(), any(), any(), any(), any());

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
        // 页面回来后凭 runId 取快照；worker 收尾必须把 run 标记为完成。
        Mockito.verify(runs).complete("run-1");
    }

    /**
     * 整页跳转会销毁 fetch，但浏览器拿不到新地址里的状态。响应头里的 runId 是页面之后
     * 重新接上这次回答的唯一凭据。
     */
    @Test
    void theResponseCarriesTheRunIdOfThisAnswer() throws Exception {
        when(providers.activeProvider()).thenReturn(Optional.of(new AiProvider()));
        when(assistant.tryStart("admin")).thenReturn(true);
        when(runs.create("admin")).thenReturn("run-1");
        Mockito.doAnswer(invocation -> {
            SupportSink sink = invocation.getArgument(3);
            sink.delta("在跑");
            return null;
        }).when(assistant).ask(any(), any(), any(), any(), any());

        mvc.perform(post("/api/support/chat").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"在跑吗\",\"history\":[]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Support-Run-Id", "run-1"));
    }

    @Test
    void theOwnerCanReattachToARun() throws Exception {
        when(runs.snapshot("run-1", "admin", 0L)).thenReturn(Optional.of(
                new SupportRunStore.Snapshot("run-1", false, false, "查到了",
                        List.of(new SupportRunStore.RunEvent(2L, "tool", Map.of("id", "c1"))), 2L)));

        mvc.perform(get("/api/support/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.text").value("查到了"))
                .andExpect(jsonPath("$.events[0].type").value("tool"))
                .andExpect(jsonPath("$.nextSeq").value(2));
    }

    /**
     * 未知 id、过期、别人的 run 一律 404：一个能区分「不存在」与「不是你的」的接口会泄露
     * 另一个账号正在进行的回答。
     */
    @Test
    void aForeignOrExpiredRunIsIndistinguishably404() throws Exception {
        when(runs.snapshot("someone-elses", "admin", 0L)).thenReturn(Optional.empty());
        when(runs.snapshot("expired", "admin", 5L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/support/runs/someone-elses")).andExpect(status().isNotFound());
        mvc.perform(get("/api/support/runs/expired").param("since", "5"))
                .andExpect(status().isNotFound());
    }

    /**
     * 断流只是「这一页不看了」：worker 还在跑，回答还应该能被接上。旧实现在 onError /
     * onTimeout 里释放单飞锁，用户切回来时同一账号被误判为忙。
     */
    @Test
    void detachStopsTheStreamWithoutFinishingTheRun() {
        SupportRunStore store = new SupportRunStore();
        String runId = store.create("admin");
        SseEmitter emitter = new SseEmitter(120_000L);
        SupportApiController.SseSink sink = new SupportApiController.SseSink(emitter, store, runId);

        sink.delta("先查");
        sink.detach();

        SupportRunStore.Snapshot snapshot = store.snapshot(runId, "admin", 0).orElseThrow();
        assertThat(snapshot.done()).isFalse();
        assertThat(snapshot.text()).isEqualTo("先查");
    }

    @Test
    void aConfirmedActionReturnsItsResultKey() throws Exception {
        when(actions.execute(Mockito.eq("tok"), any()))
                .thenReturn(new SupportActionService.Result(
                        SupportActionService.Outcome.OK, "support.action.done"));

        mvc.perform(post("/api/support/action/tok").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("support.action.done"));
    }

    @Test
    void anExpiredConfirmationIs410() throws Exception {
        when(actions.execute(Mockito.eq("gone"), any()))
                .thenReturn(new SupportActionService.Result(
                        SupportActionService.Outcome.EXPIRED, "support.action.expired"));

        mvc.perform(post("/api/support/action/gone").with(csrf()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("support.action.expired"));
    }

    @Test
    void aDeniedActionIs403() throws Exception {
        when(actions.execute(Mockito.eq("foreign"), any()))
                .thenReturn(new SupportActionService.Result(
                        SupportActionService.Outcome.DENIED, "error.accessDenied"));

        mvc.perform(post("/api/support/action/foreign").with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("error.accessDenied"));
    }

    @Test
    void aStateRefusalIs409() throws Exception {
        when(actions.execute(Mockito.eq("busy"), any()))
                .thenReturn(new SupportActionService.Result(
                        SupportActionService.Outcome.FAILED, "error.project.sync.in.progress"));

        mvc.perform(post("/api/support/action/busy").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("error.project.sync.in.progress"));
    }
}
