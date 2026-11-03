package com.qqmu.jync.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * README / 配置文档守卫（既有 {@code ReadmeConfigInstructionTest} 的 CDC 增量）：文档必须与
 * binlog CDC 的实际行为一致 —— 尤其「不碰源库 binlog 也不建触发器」那句在 v1.5.0 之后只对
 * 默认轮询模式成立，漏改就是误导。
 *
 * <p>README 只保留上手的必要信息，{@code sync.cdc.*} 键与默认值搬到
 * {@code docs/configuration.md}，守卫随之改为同时检查两份文档。英文版
 * "without reading the source's binlog and without installing triggers" 的
 * {@code doesNotContain} 仍钉住原句，避免无条件声明悄悄回来。前提关键词对位
 * {@code BinlogCdcProbe}；键与默认值对位 {@code SyncProperties.Cdc}。
 */
class CdcReadmeGuardTest {

    private static String read(String name) throws IOException {
        return Files.readString(Path.of(name), StandardCharsets.UTF_8);
    }

    @Test
    void bothReadmesDocumentCdcPrerequisites() throws IOException {
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
        }
    }

    @Test
    void bothReadmesDocumentLogMinerPrerequisitesAndCoverage() throws IOException {
        for (String name : new String[]{"README.md", "README_EN.md"}) {
            String readme = read(name);
            assertThat(readme)
                    .as("%s must mention LogMiner", name)
                    .contains("LogMiner");
            assertThat(readme)
                    .as("%s must document the ARCHIVELOG prerequisite", name)
                    .contains("ARCHIVELOG");
            assertThat(readme)
                    .as("%s must document supplemental logging", name)
                    .contains("SUPPLEMENTAL LOG");
            assertThat(readme)
                    .as("%s must document ALL COLUMNS supplemental logging", name)
                    .contains("(ALL) COLUMNS");
            // 能力表覆盖 MySQL binlog + Oracle LogMiner
            assertThat(readme)
                    .as("%s capability table must mention both MySQL binlog and Oracle LogMiner", name)
                    .contains("MySQL binlog")
                    .contains("Oracle LogMiner");
        }
    }

    @Test
    void configurationDocListsCdcKeysWithDefaults() throws IOException {
        String doc = read("docs/configuration.md");
        for (String[] keyAndDefault : new String[][]{
                {"sync.cdc.reconnect-backoff-ms", "1000"},
                {"sync.cdc.reconnect-backoff-max-ms", "60000"},
                {"sync.cdc.server-id-offset", "612000"},
                {"sync.cdc.lock-ttl-ms", "30000"},
                {"sync.cdc.supervise-interval-ms", "10000"},
                {"sync.cdc.connect-timeout-ms", "10000"},
                {"sync.cdc.logminer.batch-size", "10000"},
                {"sync.cdc.logminer.idle-poll-ms", "1000"}}) {
            assertThat(doc)
                    .as("docs/configuration.md must pair %s with its default %s",
                            keyAndDefault[0], keyAndDefault[1])
                    .contains("`" + keyAndDefault[0] + "` | `" + keyAndDefault[1] + "`");
        }
        assertThat(doc)
                .as("docs/configuration.md must show 612000 as the default fake-replica server-id offset")
                .contains("612000");
    }

    @Test
    void staleNeverTouchesBinlogClaimIsScopedToPolling() throws IOException {
        String zh = read("README.md");
        // 原句以「…」引号形式出现；把断言钉在带右引号的原形上，
        // 改写后的限定句（默认轮询模式不碰源库 binlog；…）不受影响。
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
