package com.qqmu.jync.service.ai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
        final List<ActionView> actions = new ArrayList<>();

        public void delta(String text) { events.add("delta:" + text); }
        public void tool(String callId, String name, String labelKey, ToolState state) {
            events.add("tool:" + state + ":" + name);
        }
        public void draft(DraftView draft) { events.add("draft:" + draft.token()); }
        public void action(ActionView action) {
            events.add("action:" + action.token());
            actions.add(action);
        }
        public void error(String messageKey) { events.add("error:" + messageKey); }
        public void error(String messageKey, String detail) {
            events.add("error:" + messageKey
                    + (detail == null || detail.isEmpty() ? "" : ":" + detail));
        }
        public void done() { events.add("done"); }
    }

    /**
     * A page that stayed closed past its grace period cancels the run. The worker must stop
     * before any further provider call or source-database tool execution — that is the whole
     * point of the flag.
     */
    @Test
    void aCancelledRequestStopsBeforeCallingTheModel() {
        Recorder sink = new Recorder();

        service().ask(actor, "zh", ask("订单同步在跑吗"), sink, () -> true);

        assertThat(sink.events).containsExactly("error:error.support.cancelled");
        verifyNoInteractions(chat);
    }

    @Test
    void aToolRoundIsExecutedAndItsResultFedBack() {
        AiChatClient.ToolCall call =
                new AiChatClient.ToolCall("call_1", "project_status", "{\"projectId\":7}");
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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

        // 模型在调用工具的同一轮里写的过程旁白是废话（界面已有工具进度行），不给用户看。
        assertThat(sink.events).containsExactly(
                "tool:QUEUED:project_status", "tool:RUNNING:project_status",
                "tool:DONE:project_status", "delta:它在跑");
    }

    /**
     * 流式返回的过程旁白同样不能漏出去：它在工具调用之前就到达浏览器，用户先看到一句
     * 「我先核对任务，再取证据。」，随后才出现工具进度行，正是现场反馈的废话。
     */
    @Test
    void streamedToolRoundNarrationIsHeldBackToo() {
        AiChatClient.ToolCall call =
                new AiChatClient.ToolCall("call_1", "project_status", "{\"projectId\":7}");
        java.util.concurrent.atomic.AtomicInteger rounds =
                new java.util.concurrent.atomic.AtomicInteger();
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenAnswer(inv -> {
                    java.util.function.Consumer<String> stream = inv.getArgument(6);
                    if (rounds.getAndIncrement() == 0) {
                        stream.accept("我先核对");
                        stream.accept("任务，再取证据。");
                        return new AiChatClient.ToolChatResult(
                                true, "OK", "", List.of(call), "", 1, false);
                    }
                    stream.accept("订单同步");
                    stream.accept("正在运行。");
                    return new AiChatClient.ToolChatResult(
                            true, "OK", "", List.of(), "", 1, false);
                });
        when(registry.execute(any(), any(), any())).thenReturn("{\"running\":true}");
        when(registry.isError(any())).thenReturn(false);

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("订单同步在跑吗"), sink);

        assertThat(sink.events).noneMatch(e -> e.contains("我先核对"));
        assertThat(sink.events).contains("delta:订单同步正在运行。");
    }

    /**
     * 抑制旁白不能把长回答也一起吞掉：超过一小段后照常流式下发，用户不必等整段生成完。
     */
    @Test
    void aLongAnswerStillStreamsThroughInFull() {
        String first = "一".repeat(200);
        String second = "二".repeat(200);
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenAnswer(inv -> {
                    java.util.function.Consumer<String> stream = inv.getArgument(6);
                    stream.accept(first);
                    stream.accept(second);
                    return new AiChatClient.ToolChatResult(
                            true, "OK", "", List.of(), "", 1, false);
                });

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("帮我总结一下"), sink);

        List<String> deltas = sink.events.stream()
                .filter(e -> e.startsWith("delta:"))
                .map(e -> e.substring("delta:".length()))
                .toList();
        assertThat(String.join("", deltas)).isEqualTo(first + second);
        // 超过阈值后边生成边下发，而不是攒到最后一次性给出。
        assertThat(deltas).hasSizeGreaterThan(1);
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
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        true, "OK", "hi", List.of(), "", 1, false));

        service().ask(actor, "zh", ask("hi"), new Recorder());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(chat).converseStream(any(), any(), system.capture(), any(), any(), anyInt(), any(), anyLong());
        assertThat(system.getValue())
                .contains("project_status")
                .contains("project_table_status")
                .contains("update_project_params")
                .contains("update_project_cursor")
                .contains("update_project_schedule")
                .contains("update_connection_params")
                .contains("connection_details")
                .contains("system_params")
                .contains("update_system_params")
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
     * 用户常整段粘贴报错日志，异常行在末尾。旧逻辑只保留前 4000 字，把异常无声截掉
     * （前端 maxlength 也一样）；现在超长消息保留开头与结尾、标注省略，并提示用户，
     * 模型才有机会看到真正的报错行。
     */
    @Test
    void aPastedLogKeepsItsHeadAndTailAndSaysWhatWasOmitted() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        true, "OK", "已分析", List.of(), "", 1, false));
        String log = "H".repeat(6_000) + "M".repeat(3_000) + "T".repeat(2_000);

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask(log), sink);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiChatClient.Turn>> turns = ArgumentCaptor.forClass(List.class);
        verify(chat).converseStream(any(), any(), any(), turns.capture(), any(), anyInt(), any(), anyLong());
        String sent = turns.getValue().get(0).content();
        assertThat(sent).startsWith("HHHH").endsWith("TTTT")
                .contains("已省略")
                .doesNotContain("MMMM");
        assertThat(sink.events).anyMatch(e -> e.startsWith("delta:")
                && e.contains("已保留开头与结尾"));
        assertThat(sink.events).noneMatch(e -> e.startsWith("error:"));
    }

    @Test
    void theSystemPromptExplainsHowToUseAPastedLog() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        true, "OK", "hi", List.of(), "", 1, false));

        service().ask(actor, "zh", ask("hi"), new Recorder());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(chat).converseStream(any(), any(), system.capture(), any(), any(), anyInt(), any(), anyLong());
        assertThat(system.getValue())
                .contains("粘贴")
                .contains("已省略")
                .contains("不要复述");
    }

    /**
     * The assistant may prepare a write but never perform it: the control tool's token becomes
     * a card the human must click, exactly like the project draft.
     */
    @Test
    void aControlToolProducesAConfirmationCardInsteadOfExecuting() {
        AiChatClient.ToolCall call = new AiChatClient.ToolCall(
                "c1", "control_project", "{\"projectId\":7,\"action\":\"STOP\"}");
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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

        // 「我来准备」是工具轮的过程旁白，不发给用户；卡片之后的说明才是可见正文。
        assertThat(sink.events).containsExactly(
                "tool:QUEUED:control_project", "tool:RUNNING:control_project",
                "tool:DONE:control_project", "delta:请在卡片上确认", "action:tok");
    }

    @Test
    void aParameterCardCarriesItsFieldsNoteAndAffectedTasksToTheBrowser() {
        AiChatClient.ToolCall call = new AiChatClient.ToolCall(
                "c2", "update_connection_params", "{\"connectionId\":3}");
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(
                        new AiChatClient.ToolChatResult(true, "OK", "准备中", List.of(call),
                                "", 1, false),
                        new AiChatClient.ToolChatResult(true, "OK", "请在卡片上确认", List.of(),
                                "", 1, false));
        when(registry.execute(org.mockito.ArgumentMatchers.eq("update_connection_params"),
                any(), any())).thenReturn("{\"ok\":true,\"token\":\"tok2\","
                + "\"titleKey\":\"support.action.connection.update_params\","
                + "\"resourceKindKey\":\"support.action.resource.connection\","
                + "\"resourceName\":\"prod-mysql\",\"noteKey\":\"support.action.connection.note\","
                + "\"affectedProjects\":[\"orders\",\"items\"],"
                + "\"fields\":[{\"field\":\"param:connectTimeout\","
                + "\"labelKey\":\"connectTimeout\",\"before\":\"3000\",\"after\":\"5000\","
                + "\"beforeIsDefault\":false,\"afterIsDefault\":false}]}");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("把连接超时调到 5 秒"), sink);

        SupportSink.ActionView card = sink.actions.get(0);
        assertThat(card.noteKey()).isEqualTo("support.action.connection.note");
        assertThat(card.affectedProjects()).containsExactly("orders", "items");
        assertThat(card.fields()).singleElement().satisfies(field -> {
            assertThat(field.labelKey()).isEqualTo("connectTimeout");
            assertThat(field.before()).isEqualTo("3000");
            assertThat(field.after()).isEqualTo("5000");
            assertThat(field.beforeIsDefault()).isFalse();
        });
    }

    /**
     * 工具预算用完不应该把已经取到的证据丢掉。现场反馈：四轮工具之后直接报「工具轮次
     * 过多，助手已停止」，用户拿到的是零信息。最后一轮改为不带工具再问一次，让模型
     * 基于已执行的结果作答。
     */
    @Test
    void lastToolRoundIsSynthesizedInsteadOfErroring() {
        AiChatClient.ToolCall call = new AiChatClient.ToolCall("c", "search_docs", "{}");
        AiChatClient.ToolChatResult toolRound = new AiChatClient.ToolChatResult(
                true, "OK", "", List.of(call), "", 1, false);
        AiChatClient.ToolChatResult summary = new AiChatClient.ToolChatResult(
                true, "OK", "汇总", List.of(), "", 1, false);
        // 模型每轮都只调工具、从不作答，直到预算用完：第 MAX_ROUNDS+1 次必须是不带工具
        // 的收束调用。按常量构造序列，调宽预算时这条测试不必跟着改数字。
        int budget = SupportAssistantService.MAX_ROUNDS;
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenAnswer(inv -> calls.getAndIncrement() < budget ? toolRound : summary);
        when(registry.execute(any(), any(), any())).thenReturn("{}");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("hi"), sink);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiChatClient.ToolSpec>> tools =
                ArgumentCaptor.forClass(List.class);
        verify(chat, times(budget + 1)).converseStream(any(), any(), any(), any(),
                tools.capture(), anyInt(), any(), anyLong());
        assertThat(tools.getAllValues().get(budget)).isEmpty();
        assertThat(sink.events).contains("delta:汇总");
        assertThat(sink.events).noneMatch(e -> e.startsWith("error:"));
    }

    /**
     * 现场反馈「没调用多少工具就报轮次过多」：一轮只调一个工具时，旧的 4 轮预算只够
     * 4 次查询，第 5 次就被截断。放宽后一条普通排障链路（多次查询后作答）必须完整走完。
     */
    @Test
    void aConversationMayUseMoreThanFourToolRounds() {
        AiChatClient.ToolCall call = new AiChatClient.ToolCall("c", "search_docs", "{}");
        AiChatClient.ToolChatResult toolRound = new AiChatClient.ToolChatResult(
                true, "OK", "", List.of(call), "", 1, false);
        AiChatClient.ToolChatResult answer = new AiChatClient.ToolChatResult(
                true, "OK", "已确认结论", List.of(), "", 1, false);
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(toolRound, toolRound, toolRound, toolRound, toolRound, toolRound,
                        answer);
        when(registry.execute(any(), any(), any())).thenReturn("{}");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("hi"), sink);

        verify(registry, times(6)).execute(any(), any(), any());
        assertThat(sink.events).contains("delta:已确认结论");
        assertThat(sink.events).noneMatch(e -> e.startsWith("error:"));
    }

    /**
     * 现场截图：助手回「我先核对任务，再取证据。」却不调用任何工具，下一轮就直接结束
     * 了——用户看到的是空话。计划句必须纠偏一次，让模型真正取证。
     */
    @Test
    void aPlanOnlyAnswerIsNudgedOnceAndContinued() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(
                        new AiChatClient.ToolChatResult(true, "OK", "我先核对任务，再取证据。",
                                List.of(), "", 1, false),
                        new AiChatClient.ToolChatResult(true, "OK", "当前没有告警。",
                                List.of(), "", 1, false));

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("现在有告警吗"), sink);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiChatClient.Turn>> turns = ArgumentCaptor.forClass(List.class);
        verify(chat, times(2)).converseStream(any(), any(), any(), turns.capture(), any(),
                anyInt(), any(), anyLong());
        assertThat(turns.getAllValues().get(1))
                .extracting(AiChatClient.Turn::content)
                .contains("我先核对任务，再取证据。")
                .anyMatch(text -> text.contains("不要只说明计划"));
        assertThat(sink.events).contains("delta:当前没有告警。");
        // 被纠偏的计划句只进历史，用户不该看到它——否则还是先看到一句空话。
        assertThat(sink.events).noneMatch(e -> e.contains("我先核对任务"));
        assertThat(sink.events).noneMatch(e -> e.startsWith("error:"));
    }

    /**
     * 工具已经用过一轮后，模型仍可能回一句「稍等，我再查一下。」就结束本轮。旧逻辑只在
     * 从未用过工具时纠偏，这种句子会被当成最终答案——用户只看到过渡句。纠偏同样要生效。
     */
    @Test
    void aPlanOnlySentenceAfterAToolRoundIsNudgedInsteadOfEnding() {
        AiChatClient.ToolCall call = new AiChatClient.ToolCall("c1", "search_docs", "{}");
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(
                        new AiChatClient.ToolChatResult(true, "OK", "", List.of(call),
                                "", 1, false),
                        new AiChatClient.ToolChatResult(true, "OK", "稍等，我再查一下。",
                                List.of(), "", 1, false),
                        new AiChatClient.ToolChatResult(true, "OK", "结论：没有异常。",
                                List.of(), "", 1, false));
        when(registry.execute(any(), any(), any())).thenReturn("{}");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("订单同步有没有异常"), sink);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiChatClient.Turn>> turns = ArgumentCaptor.forClass(List.class);
        verify(chat, times(3)).converseStream(any(), any(), any(), turns.capture(), any(),
                anyInt(), any(), anyLong());
        assertThat(turns.getAllValues().get(2))
                .extracting(AiChatClient.Turn::content)
                .anyMatch(text -> text != null && text.contains("不要只说明计划"));
        assertThat(sink.events).contains("delta:结论：没有异常。");
        assertThat(sink.events).noneMatch(e -> e.startsWith("error:"));
    }

    /**
     * 模型偶尔返回空正文：旧逻辑直接当成成功结束，用户拿到一个空气泡。现在先纠偏一次，
     * 再给一次不带工具的收束机会；仍然为空才明确告知，而不是静默。
     */
    @Test
    void anEmptyReplyIsNudgedAndThenReportedAfterTheSynthesisRetry() {
        AiChatClient.ToolChatResult blank = new AiChatClient.ToolChatResult(
                true, "OK", "", List.of(), "", 1, false);
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(blank, blank, blank);

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("hi"), sink);

        verify(chat, times(3)).converseStream(any(), any(), any(), any(), any(),
                anyInt(), any(), anyLong());
        assertThat(sink.events).contains("error:error.support.noAnswer");
    }

    @Test
    void anEmptyReplyCanBeRescuedByTheNoToolsSynthesis() {
        AiChatClient.ToolChatResult blank = new AiChatClient.ToolChatResult(
                true, "OK", "", List.of(), "", 1, false);
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(blank, blank, new AiChatClient.ToolChatResult(
                        true, "OK", "结论：当前没有异常。", List.of(), "", 1, false));

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("hi"), sink);

        assertThat(sink.events).contains("delta:结论：当前没有异常。");
        assertThat(sink.events).noneMatch(e -> e.startsWith("error:"));
    }

    @Test
    void anEmptyReplyFollowedByAnAnswerSucceeds() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(
                        new AiChatClient.ToolChatResult(
                                true, "OK", "", List.of(), "", 1, false),
                        new AiChatClient.ToolChatResult(
                                true, "OK", "当前没有告警。", List.of(), "", 1, false));

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("现在有告警吗"), sink);

        assertThat(sink.events).contains("delta:当前没有告警。");
        assertThat(sink.events).noneMatch(e -> e.startsWith("error:"));
    }

    /**
     * 收束轮也可能空手而归（模型只返回工具调用或空正文）。此时不能静默结束。
     */
    @Test
    void anEmptySynthesisIsReportedInsteadOfEndingSilently() {
        AiChatClient.ToolCall call = new AiChatClient.ToolCall("c", "search_docs", "{}");
        AiChatClient.ToolChatResult toolRound = new AiChatClient.ToolChatResult(
                true, "OK", "", List.of(call), "", 1, false);
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenAnswer(inv -> calls.getAndIncrement() < SupportAssistantService.MAX_ROUNDS
                        ? toolRound
                        : new AiChatClient.ToolChatResult(
                                true, "OK", "", List.of(), "", 1, false));
        when(registry.execute(any(), any(), any())).thenReturn("{}");

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("hi"), sink);

        assertThat(sink.events).contains("error:error.support.noAnswer");
    }

    @Test
    void aSecondPlanOnlyAnswerIsAcceptedWithoutASecondNudge() {
        AiChatClient.ToolChatResult plan = new AiChatClient.ToolChatResult(
                true, "OK", "我先核对任务，再取证据。", List.of(), "", 1, false);
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(plan, plan);

        Recorder sink = new Recorder();
        service().ask(actor, "zh", ask("现在有告警吗"), sink);

        verify(chat, times(2)).converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong());
        assertThat(sink.events).noneMatch(e -> e.startsWith("error:"));
    }

    @Test
    void aSubstantiveAnswerIsNotNudged() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        true, "OK", "当前没有告警。", List.of(), "", 1, false));

        service().ask(actor, "zh", ask("现在有告警吗"), new Recorder());

        verify(chat, times(1)).converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong());
    }

    /**
     * 计划句识别要保守：真正的结论里也会出现「正在运行」「准备完成」，把它们当成空话
     * 会白白多花一轮。
     */
    @Test
    void planDetectionRecognisesIntentMarkers() {
        assertThat(SupportAssistantService.isPlanOnly("我先核对任务，再取证据。")).isTrue();
        assertThat(SupportAssistantService.isPlanOnly("让我看一下位点")).isTrue();
        assertThat(SupportAssistantService.isPlanOnly("稍等，我查一下")).isTrue();
        assertThat(SupportAssistantService.isPlanOnly("接下来调用诊断工具")).isTrue();
        assertThat(SupportAssistantService.isPlanOnly("我这就查一下")).isTrue();
        assertThat(SupportAssistantService.isPlanOnly("我马上核对位点")).isTrue();
        assertThat(SupportAssistantService.isPlanOnly("请稍候，正在取数")).isTrue();
        assertThat(SupportAssistantService.isPlanOnly("Let me check the project status.")).isTrue();
        assertThat(SupportAssistantService.isPlanOnly("任务正在运行。")).isFalse();
        assertThat(SupportAssistantService.isPlanOnly("任务准备完成。")).isFalse();
        assertThat(SupportAssistantService.isPlanOnly("当前没有告警。")).isFalse();
        assertThat(SupportAssistantService.isPlanOnly("")).isFalse();
        assertThat(SupportAssistantService.isPlanOnly(null)).isFalse();
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
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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
                anyInt(), any(), anyLong());
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
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(new AiChatClient.ToolChatResult(
                        true, "OK", "ok", List.of(), "", 1, false));

        service().ask(actor, "zh", history("hi", 30), new Recorder());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiChatClient.Turn>> turns = ArgumentCaptor.forClass(List.class);
        verify(chat).converseStream(any(), any(), any(), turns.capture(), any(), anyInt(), any(), anyLong());
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
    void theSystemPromptExplainsThatLobColumnsAreSynchronizedButNotDriftCompared() {
        assertThat(SupportAssistantService.SYSTEM_PROMPT)
                .contains("不是不支持同步")
                .contains("LONGTEXT/LONGBLOB")
                .contains("内容审计")
                .contains("updated_at");
    }

    @Test
    void theSystemPromptExplainsMysqlToDamengAutoIncrementAndCase() {
        assertThat(SupportAssistantService.SYSTEM_PROMPT)
                .contains("SEQ_*")
                .contains("原生 IDENTITY")
                .contains("没有全局开关");
    }

    @Test
    void theSystemPromptExplainsExternalBaselineAndRealtimeBoundaries() {
        assertThat(SupportAssistantService.SYSTEM_PROMPT)
                .contains("外部基线接入")
                .contains("不回填表进度")
                .contains("PURGED")
                .contains("DEST_ID=1")
                .contains("无主键表")
                .contains("redis-max-len");
    }

    @Test
    void theSystemPromptExplainsArm64ForDeploymentQuestions() {
        assertThat(SupportAssistantService.SYSTEM_PROMPT)
                .contains("exec format error")
                .contains("linux/arm64")
                .contains("aarch64 是 ARM64");
    }

    @Test
    void theSystemPromptMapsKnownLogSignaturesToCauses() {
        assertThat(SupportAssistantService.SYSTEM_PROMPT)
                .contains("attrno state initialization")
                .contains("unsupported literal: TIME")
                .contains("LOGMNR_PARSE_LOB=1")
                .contains("Skipping <OP> row on")
                .contains("无主键表,仅基线播种");
    }

    /**
     * 现场反馈「不会说废话」：开场白、复述问题、过程旁白、结尾客套都要在提示词里禁掉，
     * 否则模型即使结论正确也会先铺陈三句。简单问题必须允许一两句答完。
     */
    @Test
    void theSystemPromptBansFillerOpenersClosersAndProcessNarration() {
        assertThat(SupportAssistantService.SYSTEM_PROMPT)
                .contains("不写「好的」「当然」「没问题」")
                .contains("不复述用户的问题")
                .contains("不写过程旁白")
                .contains("不写结尾客套")
                .contains("一句话能答完就只写一句");
    }

    /**
     * 工具轮次用尽后的收束轮是最容易说废话的地方（「根据以上查询结果」「轮次已达上限」），
     * 这一次调用要额外带上收束要求。
     */
    @Test
    void theSynthesisRoundAsksForAFinalAnswerWithoutProcessTalk() {
        AiChatClient.ToolCall call = new AiChatClient.ToolCall("c", "search_docs", "{}");
        AiChatClient.ToolChatResult toolRound = new AiChatClient.ToolChatResult(
                true, "OK", "", List.of(call), "", 1, false);
        AiChatClient.ToolChatResult summary = new AiChatClient.ToolChatResult(
                true, "OK", "汇总", List.of(), "", 1, false);
        int budget = SupportAssistantService.MAX_ROUNDS;
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenAnswer(inv -> calls.getAndIncrement() < budget ? toolRound : summary);
        when(registry.execute(any(), any(), any())).thenReturn("{}");

        service().ask(actor, "zh", ask("hi"), new Recorder());

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(chat, times(budget + 1)).converseStream(any(), any(), system.capture(), any(),
                any(), anyInt(), any(), anyLong());
        assertThat(system.getAllValues().get(budget))
                .startsWith(SupportAssistantService.SYSTEM_PROMPT)
                .contains("本轮请直接给出最终回答");
        assertThat(system.getAllValues().get(0))
                .isEqualTo(SupportAssistantService.SYSTEM_PROMPT);
    }

    @Test
    void aFailedModelCallIsReportedAndAudited() {
        when(chat.converseStream(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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
