package com.qqmu.jync.service.ai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.ai.AiChatClient;
import com.qqmu.jync.service.ai.AiProviderService;
import com.qqmu.jync.service.auth.AccessGuard;

class SupportAssistantServiceTest {

    private final AiChatClient chat = mock(AiChatClient.class);
    private final AiProviderService providers = mock(AiProviderService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final SupportToolRegistry registry = mock(SupportToolRegistry.class);
    private final AccessGuard actor = mock(AccessGuard.class);
    private final AiProvider provider = new AiProvider();

    private SupportAssistantService service() {
        when(providers.activeProvider()).thenReturn(Optional.of(provider));
        when(providers.decryptKey(provider)).thenReturn("k");
        when(registry.definitions()).thenReturn(List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "search_docs",
                        "description", "docs",
                        "parameters", Map.of("type", "object")))));
        return new SupportAssistantService(chat, providers, registry, audit);
    }

    private static SupportChatRequest ask(String text) {
        return new SupportChatRequest(text, List.of());
    }

    private static SupportChatRequest history(String text, int messages) {
        List<SupportChatRequest.HistoryItem> items = new ArrayList<>();
        for (int i = 0; i < messages; i++) {
            items.add(new SupportChatRequest.HistoryItem(i % 2 == 0 ? "user" : "assistant", "h"));
        }
        return new SupportChatRequest(text, items);
    }

    private static final class Recorder implements SupportSink {
        final List<String> events = new ArrayList<>();

        public void delta(String text) { events.add("delta:" + text); }
        public void tool(String name, String labelKey) { events.add("tool:" + name); }
        public void draft(DraftView draft) { events.add("draft:" + draft.token()); }
        public void error(String messageKey) { events.add("error:" + messageKey); }
        public void done() { events.add("done"); }
    }

    @Test
    void aToolRoundIsExecutedAndItsResultFedBack() {
        AiChatClient.ToolCall call =
                new AiChatClient.ToolCall("call_1", "project_status", "{\"projectId\":7}");
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(
                        new AiChatClient.ToolChatResult(true, "OK", "我先查", List.of(call),
                                "", 1, false),
                        new AiChatClient.ToolChatResult(true, "OK", "它在跑", List.of(),
                                "", 1, false));
        when(registry.execute(org.mockito.ArgumentMatchers.eq("project_status"),
                org.mockito.ArgumentMatchers.eq("{\"projectId\":7}"), any()))
                .thenReturn("{\"running\":true}");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("订单同步在跑吗"), sink);

        assertThat(sink.events).containsExactly("delta:我先查", "tool:project_status",
                "delta:它在跑");
    }

    @Test
    void toolRoundLimitStopsTheLoop() {
        AiChatClient.ToolCall call = new AiChatClient.ToolCall("c", "search_docs", "{}");
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        true, "OK", "x", List.of(call), "", 1, false));
        when(registry.execute(any(), any(), any())).thenReturn("{}");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("hi"), sink);

        assertThat(sink.events).contains("error:error.support.tooManyRounds");
        verify(chat, times(4)).converseStream(any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void toolUnsupportedRetriesOnceWithoutTools() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(
                        new AiChatClient.ToolChatResult(false, "HTTP 400", "", List.of(),
                                "", 1, true),
                        new AiChatClient.ToolChatResult(true, "OK", "仅文档回答", List.of(),
                                "", 1, false));

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("CDC 怎么配"), sink);

        assertThat(sink.events.get(0)).startsWith("delta:");
        assertThat(sink.events).contains("delta:仅文档回答");
        assertThat(sink.events).noneMatch(e -> e.startsWith("tool:"));
    }

    @Test
    void anEmptyMessageIsRejectedBeforeAnyModelCall() {
        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("   "), sink);
        assertThat(sink.events).containsExactly("error:error.support.empty");
        verifyNoInteractions(chat);
    }

    @Test
    void historyIsTruncatedToTheLastTwelveMessages() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        true, "OK", "ok", List.of(), "", 1, false));

        service().ask(actor, "zh", history("hi", 30), new Recorder());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiChatClient.Turn>> turns = ArgumentCaptor.forClass(List.class);
        verify(chat).converseStream(any(), any(), any(), turns.capture(), any(), anyInt(), any());
        // 12 条历史 + 当前问题。
        assertThat(turns.getValue()).hasSize(13);
    }

    @Test
    void oneUserMayHaveOnlyOneRequestInFlight() {
        SupportAssistantService service = service();
        assertThat(service.tryStart("alice")).isTrue();
        assertThat(service.tryStart("alice")).isFalse();
        service.finish("alice");
        assertThat(service.tryStart("alice")).isTrue();
    }

    @Test
    void aMissingProviderIsReportedAsDisabled() {
        when(providers.activeProvider()).thenReturn(Optional.empty());
        Recorder sink = new Recorder();
        new SupportAssistantService(chat, providers, registry, audit)
                .ask(actor, "zh", ask("hi"), sink);
        assertThat(sink.events).containsExactly("error:error.ai.disabled");
    }

    @Test
    void aFailedModelCallIsReportedAndAudited() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        false, "HTTP 500", "", List.of(), "", 1, false));
        when(actor.username()).thenReturn("ops1");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("hi"), sink);

        assertThat(sink.events).containsExactly("error:error.ai.requestFailed");
        verify(audit).record(org.mockito.ArgumentMatchers.eq(
                        com.qqmu.jync.model.AuditAction.AI_SUPPORT_ASK),
                org.mockito.ArgumentMatchers.contains("success=false"), any());
    }
}
