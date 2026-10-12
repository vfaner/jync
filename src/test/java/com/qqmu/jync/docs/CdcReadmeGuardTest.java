package com.qqmu.jync.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * README 守卫（既有 {@code ReadmeConfigInstructionTest} 的 CDC 增量）：文档必须与 binlog CDC
 * 的实际行为一致 —— 尤其「不碰源库 binlog 也不建触发器」那句在 v1.5.0 之后只对默认轮询模式
 * 成立，漏改就是误导。
 *
 * <p>The stale claim lived inside a quoted premise:
 * 「用普通查询做增量、不碰源库 binlog 也不建触发器」这个前提 (README.md:559) and its English
 * twin "without reading the source's binlog and without installing triggers"
 * (README_EN.md:545). The {@code doesNotContain} pins below keep the exact quoted/trailing
 * context so the rewritten, polling-scoped sentences pass while the unconditional claim can
 * never silently come back. Prerequisite wording mirrors {@code BinlogCdcProbe}; the six
 * {@code sync.cdc.*} keys and defaults mirror {@code SyncProperties.Cdc}.
 */
class CdcReadmeGuardTest {

    private static String read(String name) throws IOException {
        return Files.readString(Path.of(name), StandardCharsets.UTF_8);
    }

    @Test
    void bothReadmesDocumentCdcPrerequisitesAndConfig() throws IOException {
        for (String name : new String[]{"README.md", "README_EN.md"}) {
            String readme = read(name);
            // 前提清单（BinlogCdcProbe 的七项检查）
            assertThat(readme)
                    .as("%s must document the ROW binlog_format prerequisite", name)
                    .contains("binlog_format");
            assertThat(readme)
                    .as("%s must document the FULL binlog_row_image prerequisite", name)
                    .contains("binlog_row_image");
            assertThat(readme)
                    .as("%s must document both replication grant prerequisites", name)
                    .contains("REPLICATION SLAVE")
                    .contains("REPLICATION CLIENT");
            // 配置表：SyncProperties.Cdc 的六个键，一个不少
            for (String key : new String[]{
                    "sync.cdc.reconnect-backoff-ms",
                    "sync.cdc.reconnect-backoff-max-ms",
                    "sync.cdc.server-id-offset",
                    "sync.cdc.lock-ttl-ms",
                    "sync.cdc.supervise-interval-ms",
                    "sync.cdc.connect-timeout-ms"}) {
                assertThat(readme)
                        .as("%s must list %s in its configuration table", name, key)
                        .contains(key);
            }
            assertThat(readme)
                    .as("%s must show 612000 as the default fake-replica server-id offset", name)
                    .contains("612000");
            // 默认值与键同行钉死：配置表的「`键` | `默认值`」片段在两种语言里渲染完全一致，
            // 因此钉行片段而非裸数字 —— 任何一个键的默认值被改错都会触发守卫，
            // 又不会被正文里偶然出现的 "1000"/"60000" 之类误满足。
            for (String[] keyAndDefault : new String[][]{
                    {"sync.cdc.reconnect-backoff-ms", "1000"},
                    {"sync.cdc.reconnect-backoff-max-ms", "60000"},
                    {"sync.cdc.server-id-offset", "612000"},
                    {"sync.cdc.lock-ttl-ms", "30000"},
                    {"sync.cdc.supervise-interval-ms", "10000"},
                    {"sync.cdc.connect-timeout-ms", "10000"}}) {
                assertThat(readme)
                        .as("%s must pair %s with its default %s in the configuration table",
                                name, keyAndDefault[0], keyAndDefault[1])
                        .contains("`" + keyAndDefault[0] + "` | `" + keyAndDefault[1] + "`");
            }
        }
    }

    @Test
    void bothReadmesDocumentLogMinerCdcPrerequisitesAndConfig() throws IOException {
        for (String name : new String[]{"README.md", "README_EN.md"}) {
            String readme = read(name);
            // 章节标题（双语）
            assertThat(readme)
                    .as("%s must have a LogMiner CDC section", name)
                    .contains("LogMiner");
            // 七项探测前提关键词（对位 LogMinerCdcProbe）
            assertThat(readme)
                    .as("%s must document ARCHIVELOG prerequisite", name)
                    .contains("ARCHIVELOG");
            assertThat(readme)
                    .as("%s must document supplemental logging prerequisite", name)
                    .contains("SUPPLEMENTAL LOG");
            assertThat(readme)
                    .as("%s must document ALL COLUMNS supplemental logging", name)
                    .contains("(ALL) COLUMNS");
            // 配置表两新键行（默认值钉死）
            assertThat(readme)
                    .as("%s must pair sync.cdc.logminer.batch-size with 10000", name)
                    .contains("`sync.cdc.logminer.batch-size` | `10000`");
            assertThat(readme)
                    .as("%s must pair sync.cdc.logminer.idle-poll-ms with 1000", name)
                    .contains("`sync.cdc.logminer.idle-poll-ms` | `1000`");
            // 能力对比行更新：日志级覆盖 MySQL binlog + Oracle LogMiner
            assertThat(readme)
                    .as("%s comparison row must mention both MySQL binlog and Oracle LogMiner", name)
                    .contains("MySQL binlog")
                    .contains("Oracle LogMiner");
        }
    }

    @Test
    void staleNeverTouchesBinlogClaimIsScopedToPolling() throws IOException {
        String zh = read("README.md");
        // 原句以「…」引号形式出现；把断言钉在带右引号的原形上，
        // 改写后的限定句（默认轮询模式不碰源库 binlog 也不建触发器；…）不受影响。
        assertThat(zh)
                .as("README.md must not carry the unconditional 不碰源库-binlog claim anymore")
                .doesNotContain("不碰源库 binlog 也不建触发器」");
        assertThat(zh)
                .as("README.md must scope the claim to the default polling mode")
                .contains("轮询模式不碰源库 binlog");

        String en = read("README_EN.md");
        assertThat(en)
                .as("README_EN.md must not carry the unconditional never-touches-binlog claim anymore")
                .doesNotContain("without reading the source's binlog and without installing triggers");
        assertThat(en)
                .as("README_EN.md must scope the claim to the default polling mode")
                .contains("polling mode never reads the source's binlog");
    }
}
