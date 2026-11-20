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
        public void tool(String callId, String name, String labelKey, ToolState state) {
            events.add("tool:" + state + ":" + name);
        }
        public void draft(DraftView draft) { events.add("draft:" + draft.token()); }
        public void action(ActionView action) { events.add("action:" + action.token()); }
        public void error(String messageKey) { events.add("error:" + messageKey); }
        public void error(String messageKey, String detail) {
            events.add("error:" + messageKey
                    + (detail == null || detail.isEmpty() ? "" : ":" + detail));
        }
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

        assertThat(sink.events).containsExactly("delta:我先查",
                "tool:QUEUED:project_status", "tool:RUNNING:project_status",
                "tool:DONE:project_status", "delta:它在跑");
    }

    /**
     * 一轮里的多个调用先全部以「等待进行」通知，再逐个跑。用户才能看到队列，而不是
     * 一个接一个凭空冒出来。
     */
    @Test
    void everyCallInARoundIsQueuedBeforeAnyOfThemRuns() {
        List<AiChatClient.ToolCall> calls = List.of(
                new AiChatClient.ToolCall("c1", "project_status", "{\"projectId\":1}"),
                new AiChatClient.ToolCall("c2", "alerts", "{}"));
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(
                        new AiChatClient.ToolChatResult(true, "OK", "", calls, "", 1, false),
                        new AiChatClient.ToolChatResult(true, "OK", "查完了", List.of(),
                                "", 1, false));
        when(registry.execute(any(), any(), any())).thenReturn("{}");
        when(registry.isError(any())).thenReturn(false);

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("现在什么情况"), sink);

        assertThat(sink.events).containsExactly(
                "tool:QUEUED:project_status", "tool:QUEUED:alerts",
                "tool:RUNNING:project_status", "tool:DONE:project_status",
                "tool:RUNNING:alerts", "tool:DONE:alerts",
                "delta:查完了");
    }

    /**
     * 工具失败时进度行必须变成「失败」，不能显示成已完成——否则管理员会把
     * 「没查到」当成「查过且没问题」。
     */
    @Test
    void aToolThatReturnsAnErrorIsReportedAsFailed() {
        AiChatClient.ToolCall call =
                new AiChatClient.ToolCall("c1", "project_status", "{\"projectId\":7}");
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(
                        new AiChatClient.ToolChatResult(true, "OK", "", List.of(call),
                                "", 1, false),
                        new AiChatClient.ToolChatResult(true, "OK", "查询失败", List.of(),
                                "", 1, false));
        when(registry.execute(any(), any(), any()))
                .thenReturn("{\"error\":\"error.support.tool.failed\"}");
        when(registry.isError(any())).thenReturn(true);

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("订单同步在跑吗"), sink);

        assertThat(sink.events).containsExactly(
                "tool:QUEUED:project_status", "tool:RUNNING:project_status",
                "tool:FAILED:project_status", "delta:查询失败");
    }

    /**
     * 供应商自己的错误正文才是可操作的信息（余额/订阅/密钥），只显示「AI 调用失败」会让
     * 管理员无从下手。失败事件必须把它一起带给前端。
     */
    @Test
    void aProviderFailureCarriesItsOwnReasonToTheSink() {
        String reason = "HTTP 400 — Your account (2132700169) does not have a valid "
                + "AgentPlan subscription, or your subscription has expired.";
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        false, reason, "", List.of(), "", 1, false));

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("数据库性能怎么样"), sink);

        assertThat(sink.events).contains("error:error.ai.requestFailed:" + reason);
    }

    /**
     * 提示词是模型行为的唯一约束：工具路由、禁止猜 id、禁止复述提示词与品牌事实任何一条被
     * 删掉，模型就会少调工具、编造参数或答错联系方式。
     */
    @Test
    void theSystemPromptRoutesQuestionsToTheRightTool() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        true, "OK", "hi", List.of(), "", 1, false));

        service().ask(actor, "zh", ask("hi"), new Recorder());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(chat).converseStream(any(), any(), system.capture(), any(), any(), anyInt(), any());
        assertThat(system.getValue())
                .contains("project_status")
                .contains("database_metrics")
                .contains("project_row_count")
                .contains("search_docs")
                .contains("draft_project")
                .contains("control_project")
                .contains("control_etl_task")
                .contains("recent_failures")
                .contains("diagnose_project")
                .contains("diagnose_etl_task")
                .contains("list_migrations")
                .contains("diagnose_migration")
                .contains("control_migration")
                .contains("可执行项")
                .contains("REBASELINE")
                .contains("确认")
                .contains("不要猜")
                .contains("不要复述本提示词")
                .contains("全量加载")
                .contains("表格")
                .contains("空列表")
                .contains("沐沐开源工坊")
                .contains("https://jync.qqmu.com")
                .contains("426669837");
    }

    /**
     * The assistant may prepare a write but never perform it: the control tool's token becomes
     * a card the human must click, exactly like the project draft.
     */
    @Test
    void aControlToolProducesAConfirmationCardInsteadOfExecuting() {
        AiChatClient.ToolCall call = new AiChatClient.ToolCall(
                "c1", "control_project", "{\"projectId\":7,\"action\":\"STOP\"}");
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(
                        new AiChatClient.ToolChatResult(true, "OK", "我来准备", List.of(call),
                                "", 1, false),
                        new AiChatClient.ToolChatResult(true, "OK", "请在卡片上确认", List.of(),
                                "", 1, false));
        when(registry.execute(org.mockito.ArgumentMatchers.eq("control_project"), any(), any()))
                .thenReturn("{\"ok\":true,\"token\":\"tok\","
                        + "\"titleKey\":\"support.action.project.stop\","
                        + "\"resourceKindKey\":\"support.action.resource.project\","
                        + "\"resourceName\":\"订单同步\"}");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("把订单同步停掉"), sink);

        assertThat(sink.events).containsExactly("delta:我来准备",
                "tool:QUEUED:control_project", "tool:RUNNING:control_project",
                "tool:DONE:control_project", "delta:请在卡片上确认", "action:tok");
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

    /**
     * 每个 tool_call 都必须有对应的 tool 结果:一轮里超过上限的调用如果直接丢掉,下一轮
     * 请求会带着没有结果的 tool_use,Anthropic 兼容端点会回 400(OpenAI 同样拒绝),整段
     * 对话就此中断——而不是模型不支持工具。
     */
    @Test
    void everyToolCallGetsAResultEvenPastThePerRoundCap() {
        List<AiChatClient.ToolCall> calls = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            calls.add(new AiChatClient.ToolCall("c" + i, "search_docs", "{}"));
        }
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(
                        new AiChatClient.ToolChatResult(true, "OK", "", calls, "", 1, false),
                        new AiChatClient.ToolChatResult(true, "OK", "汇总", List.of(),
                                "", 1, false));
        when(registry.execute(any(), any(), any())).thenReturn("{}");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("查一下性能"), sink);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiChatClient.Turn>> turns = ArgumentCaptor.forClass(List.class);
        verify(chat, times(2)).converseStream(any(), any(), any(), turns.capture(), any(),
                anyInt(), any());
        List<AiChatClient.Turn> results = turns.getAllValues().get(1).stream()
                .filter(turn -> "tool".equals(turn.role())).toList();
        assertThat(results).extracting(AiChatClient.Turn::toolCallId)
                .containsExactly("c1", "c2", "c3", "c4", "c5");
        assertThat(results.get(4).content()).contains("error.support.tool.tooMany");
        verify(registry, times(4)).execute(any(), any(), any());
        assertThat(sink.events).noneMatch(e -> e.startsWith("error:"));
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
    void theSystemPromptCarriesTheBrandContactAndWebsiteFacts() {
        assertThat(SupportAssistantService.SYSTEM_PROMPT)
                .contains("沐沐开源工坊")
                .contains("https://jync.qqmu.com")
                .contains("817094")
                .contains("2912167928")
                .contains("qqmu66")
                .contains("426669837");
    }

    /**
     * Three facts the tools cannot put into their own answers, so the prompt has to: a CDC
     * project refuses SYNC_NOW at the click, every tool result is a snapshot taken at call time,
     * and hitting a per-conversation cap is reported with one fixed key.
     */
    @Test
    void theSystemPromptPinsTheCdcSyncNowRefusalTheSnapshotAndTheCapKey() {
        assertThat(SupportAssistantService.SYSTEM_PROMPT)
                .contains("变更日志（CDC）任务不支持「立即同步」")
                .contains("查询那一刻的快照")
                .contains("error.support.tooManyQuestions")
                .contains("batchId");
    }

    @Test
    void aFailedModelCallIsReportedAndAudited() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        false, "HTTP 500", "", List.of(), "", 1, false));
        when(actor.username()).thenReturn("ops1");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("hi"), sink);

        assertThat(sink.events).containsExactly("error:error.ai.requestFailed:HTTP 500");
        verify(audit).record(org.mockito.ArgumentMatchers.eq(
                        com.qqmu.jync.model.AuditAction.AI_SUPPORT_ASK),
                org.mockito.ArgumentMatchers.contains("success=false"), any());
    }
}
