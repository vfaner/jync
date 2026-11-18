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
    void noMatchReturnsEmptyRatherThanEverything() {
        assertThat(kb.search("zh", "zzzz qqqq", 3)).isEmpty();
    }

    @Test
    void topKIsHonoured() {
        var hits = kb.search("zh", "同步 连接 任务", 2);
        assertThat(hits).hasSizeLessThanOrEqualTo(2);
    }
}
