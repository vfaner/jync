package com.qqmu.jync.service.ai.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupportKnowledgeBaseTest {

    private final SupportKnowledgeBase kb = new SupportKnowledgeBase();

    @Test
    void chineseQueryFindsTheOracleCdcSection() {
        var hits = kb.search("zh", "Oracle CDC 怎么配置挖掘账号", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).title()).isEqualTo("Oracle CDC 配置");
        assertThat(hits.get(0).body()).contains("minerUsername");
    }

    @Test
    void englishQueryFindsTheEtlSection() {
        var hits = kb.search("en", "ETL variables month", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).title()).isEqualTo("ETL tasks and variables");
    }

    @Test
    void aChineseAuthorQuestionFindsTheBrandSection() {
        var hits = kb.search("zh", "Jync 的作者是谁，怎么联系", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("作者与联系方式");
            assertThat(hit.body())
                    .contains("沐沐开源工坊")
                    .contains("https://jync.qqmu.com")
                    .contains("qqmu66")
                    .contains("426669837");
        });
    }

    @Test
    void anEnglishAuthorQuestionFindsTheBrandSection() {
        var hits = kb.search("en", "who maintains Jync, how can I contact the author", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("Author and contact");
            assertThat(hit.body())
                    .contains("沐沐开源工坊")
                    .contains("https://jync.qqmu.com")
                    .contains("qqmu66")
                    .contains("426669837");
        });
    }

    @Test
    void aChineseFanoutQuestionFindsTheFanoutSection() {
        var hits = kb.search("zh", "一源多目标 fan-out 20 个目标 Redis Stream 中转", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("一源多目标（fan-out）");
            assertThat(hit.body()).contains("sync.fanout.max-targets");
        });
    }

    @Test
    void anEnglishFanoutQuestionFindsTheFanoutSection() {
        var hits = kb.search("en", "one source many targets fan-out Redis Stream transport", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit ->
                assertThat(hit.title()).isEqualTo("One source, many targets (fan-out)"));
    }

    @Test
    void aChineseQuestionAboutLongTextDriftFindsTheLobSection() {
        var hits = kb.search("zh", "LONGTEXT LONGBLOB 不参与漂移比对 是不是不支持同步", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("大字段与漂移比对（LONGTEXT / LONGBLOB）");
            assertThat(hit.body())
                    .contains("不是不支持同步")
                    .contains("内容级漂移比对")
                    .contains("updated_at");
        });
    }

    @Test
    void anEnglishQuestionAboutLongTextDriftFindsTheLobSection() {
        var hits = kb.search("en", "LONGTEXT LONGBLOB excluded from drift comparison supported",
                3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit ->
                assertThat(hit.title()).isEqualTo("Large columns and drift comparison (LONGTEXT / LONGBLOB)"));
    }

    @Test
    void aChineseQuestionAboutTdengineFindsTheTimeSeriesSection() {
        var hits = kb.search("zh", "TDengine 时序数据库怎么同步", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("TDengine 与时序数据");
            assertThat(hit.body()).contains("taos-jdbcdriver-3.10.0-dist.jar");
        });
    }

    @Test
    void anEnglishQuestionAboutTdengineFindsTheTimeSeriesSection() {
        var hits = kb.search("en", "TDengine time-series source ETL target", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("TDengine and time-series data");
            assertThat(hit.body()).contains("taos-jdbcdriver-3.10.0-dist.jar");
        });
    }

    /**
     * The question that follows every CDC outage: "does rebuilding the baseline also fix the
     * rows deleted at the source?" The answer has to be in the bundled documentation, because
     * the assistant may only answer from it.
     */
    @Test
    void aChineseQuestionAboutRebaselineAndDeletesFindsTheNewSection() {
        var hits = kb.search("zh", "重建基线 会清空目标表吗 同步删除 源端删除的行", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("CDC 停摆与重建基线");
            assertThat(hit.body()).contains("upsert").contains("同步删除");
        });
    }

    @Test
    void anEnglishQuestionAboutRebaselineAndDeletesFindsTheNewSection() {
        var hits = kb.search("en",
                "rebuild baseline delete target rows sync deletes source deleted", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("CDC stalls and rebuilding the baseline");
            assertThat(hit.body()).contains("upsert").contains("sync deletes");
        });
    }

    /**
     * An operator who asks about the assistant's own diagnostic flow must be told which call
     * gathers the evidence and that suggested actions arrive as confirmation cards, not as
     * already-executed changes.
     */
    @Test
    void theAssistantSectionExplainsTheOneCallDiagnosisAndSelectableActions() {
        var zh = kb.search("zh", "AI 运维助手 诊断 建议 可执行项 确认卡", 3);
        assertThat(zh).isNotEmpty();
        assertThat(zh).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("AI 运维助手能查什么");
            assertThat(hit.body()).contains("diagnose_project").contains("可执行项");
        });

        var en = kb.search("en",
                "AI support assistant diagnosis suggestions executable confirmation card", 3);
        assertThat(en).isNotEmpty();
        assertThat(en).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("What the AI operations assistant can do");
            assertThat(hit.body()).contains("diagnose_project").contains("executable");
        });
    }

    /**
     * The English assistant section used to be cut at 2,000 characters, dropping the
     * diagnosis-order and row-count bullets — the model then answered "what can the assistant
     * do" from the opening paragraphs only. The excerpt must reach those later bullets, and
     * the confirmation-card boundary now lives in its own section so it survives the cut.
     */
    @Test
    void theEnglishAssistantSectionKeepsItsLaterBullets() {
        var en = kb.search("en",
                "AI support assistant diagnosis suggestions executable confirmation card", 3);
        String all = en.stream().map(SupportKnowledgeBase.Section::body)
                .reduce("", String::concat);
        assertThat(all).contains("Suggested order").contains("confirmation card");
    }

    @Test
    void aPastedBatchWriteErrorFindsTheErrorLogSection() {
        var hits = kb.search("zh",
                "Batch write failed Column not found in upsert attrno state initialization 海量", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("报错日志速查");
            assertThat(hit.body())
                    .contains("attrno state initialization")
                    .contains("lower_case_column_names");
        });
    }

    @Test
    void aDamengLobQuestionFindsTheLobSection() {
        var hits = kb.search("zh",
                "达梦 OUT_CLOB OUT_BLOB LOGMNR_PARSE_LOB LOB_MAX_INROW_LEN 跳过缺失 LOB", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("达梦 LogMiner 跳过事件与 LOB");
            assertThat(hit.body())
                    .contains("LOGMNR_PARSE_LOB")
                    .contains("8KB")
                    .contains("missing-lob-policy");
        });
    }

    @Test
    void aDockerArm64QuestionFindsTheDeploymentSection() {
        var hits = kb.search("zh", "Docker ARM64 aarch64 exec format error 麒麟 部署", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("Docker 部署与 ARM64");
            assertThat(hit.body()).contains("aarch64").contains("linux/arm64").contains("exec format error");
        });
    }

    /** The per-section cap times topK must still fit the registry's result cap. */
    @Test
    void excerptsStayWithinTheTotalBudget() {
        var hits = kb.search("en", "sync task failure connection timeout retry baseline", 5);
        int total = hits.stream().mapToInt(hit -> hit.body().length()).sum();
        assertThat(total).isLessThanOrEqualTo(6001);
    }

    @Test
    void noMatchReturnsEmptyRatherThanEverything() {
        assertThat(kb.search("zh", "zzzz qqqq", 3)).isEmpty();
    }

    @Test
    void topKIsHonoured() {
        var hits = kb.search("zh", "同步 连接 任务", 2);
        assertThat(hits).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void aChineseQuestionAboutExternalBaselineFindsTheAdoptionSection() {
        var hits = kb.search("zh", "外部基线 dump 跳过全量 binlog 位点", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("外部基线接入（跳过首次全量）");
            assertThat(hit.body()).contains("RESTART_POSITION").contains("NOT_STARTED");
        });
    }

    @Test
    void anEnglishQuestionAboutKeylessTablesFindsTheLimitationSection() {
        var hits = kb.search("en", "keyless table no primary key full load", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit ->
                assertThat(hit.title()).isEqualTo("Keyless tables"));
    }

    @Test
    void aChineseQuestionAboutMysqlAutoIncrementToDamengFindsTheSection() {
        var hits = kb.search("zh", "mysql 自增 同步到达梦 丢失 IDENTITY 序列", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("MySQL 自增同步到达梦");
            assertThat(hit.body()).contains("SEQ_").contains("TRG_");
        });
    }

    @Test
    void anEnglishQuestionAboutMysqlAutoIncrementToDamengFindsTheSection() {
        var hits = kb.search("en", "mysql auto increment lost Dameng identity sequence", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit ->
                assertThat(hit.title()).isEqualTo("MySQL auto-increment synced to Dameng"));
    }

    @Test
    void aChineseQuestionAboutRepeatedOra01291FindsTheSection() {
        var hits = kb.search("zh", "ORA-01291 重建基线 还是报错 归档 DEST_ID", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.title()).isEqualTo("LogMiner ORA-01291 反复重建仍失败");
            assertThat(hit.body()).contains("DEST_ID").contains("FIRST_CHANGE#");
        });
    }
}
