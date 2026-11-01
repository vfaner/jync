package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 任务详情页按「一张卡一个模块」组装：共享卡片、轮询专属卡片、CDC 专属卡片各自成文件，
 * 壳模板只负责装配顺序与表单/两列骨架。
 *
 * <p>这是结构约束而不是渲染断言——渲染结果由 Project*ViewTest 那一整套覆盖。这里断言的是
 * 「改 CDC 摸不到轮询」「改轮询摸不到 CDC」这条边界真的落在文件上：壳里不再内联任何卡片
 * 标记，每种卡片只在自己的模块文件里定义一次。
 */
class ProjectDetailTemplateAssemblyTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path DETAIL = TEMPLATES.resolve("project-detail");

    @Test
    void theShellAssemblesEveryCardFromADedicatedModule() throws IOException {
        String shell = read(TEMPLATES.resolve("project-detail.html"));

        assertThat(shell)
                .contains("project-detail/head :: head")
                .contains("project-detail/controls :: card")
                .contains("project-detail/strategy :: card")
                .contains("project-detail/objects :: card")
                .contains("project-detail/options :: card")
                .contains("project-detail/schedule :: card")
                .contains("project-detail/cdc-schedule-hint :: card")
                .contains("project-detail/performance :: card")
                .contains("project-detail/cdc-status :: cdc-card")
                .contains("project-detail/cdc-idle-poll :: card")
                .contains("project-detail/column-rules :: card")
                .contains("project-detail/progress :: block");
    }

    @Test
    void theShellKeepsOnlyFormScaffoldingAndNoCardMarkup() throws IOException {
        String shell = read(TEMPLATES.resolve("project-detail.html"));

        assertThat(shell)
                .contains("<form id=\"config-form\"")
                .contains("class=\"split-wide\"")
                .contains("<fieldset class=\"bare\"")
                .doesNotContain("id=\"cdc-card\"")
                .doesNotContain("name=\"captureStrategy\"")
                .doesNotContain("data-role=\"polling-only\"")
                .doesNotContain("data-cdc-visibility=")
                .doesNotContain("id=\"columnRuleTable\"")
                .doesNotContain("id=\"row-count-modal\"");
    }

    @Test
    void everyModuleFileDefinesTheFragmentTheShellIncludes() throws IOException {
        assertFragment("head.html", "head");
        assertFragment("controls.html", "card");
        assertFragment("strategy.html", "card");
        assertFragment("objects.html", "card");
        assertFragment("options.html", "card");
        assertFragment("baseline-options.html", "group");
        assertFragment("schedule.html", "card");
        assertFragment("cdc-schedule-hint.html", "card");
        assertFragment("performance.html", "card");
        assertFragment("cdc-status.html", "cdc-card");
        assertFragment("cdc-idle-poll.html", "card");
        assertFragment("column-rules.html", "card");
        assertFragment("progress.html", "block");
    }

    @Test
    void pollingAndCdcCardsNeverShareAModule() throws IOException {
        assertThat(read(DETAIL.resolve("schedule.html")))
                .contains("data-role=\"polling-only\"")
                .doesNotContain("data-cdc-visibility=\"polling-cdc\"");
        assertThat(read(DETAIL.resolve("cdc-schedule-hint.html")))
                .contains("data-role=\"cdc-schedule-hint\"");
        assertThat(read(DETAIL.resolve("cdc-status.html")))
                .contains("id=\"cdc-card\"")
                .doesNotContain("id=\"cronExpression\"");
        assertThat(read(DETAIL.resolve("cdc-idle-poll.html")))
                .contains("data-cdc-visibility=\"polling-cdc\"")
                .doesNotContain("data-role=\"polling-only\"");
    }

    @Test
    void theOptionsCardAssemblesItsPollingSwitchesAndBaselineGroup() throws IOException {
        String options = read(DETAIL.resolve("options.html"));

        assertThat(options)
                .contains("name=\"syncStructure\"")
                .contains("name=\"syncData\"")
                .contains("name=\"syncIndexes\"")
                .contains("project-detail/baseline-options :: group")
                .doesNotContain("name=\"syncViews\"");

        assertThat(read(DETAIL.resolve("baseline-options.html")))
                .contains("name=\"syncViews\"")
                .contains("name=\"syncProcedures\"")
                .contains("name=\"allowDrop\"");
    }

    private static void assertFragment(String file, String fragment) throws IOException {
        Path path = DETAIL.resolve(file);
        assertThat(path)
                .as("module file %s", file)
                .exists();
        assertThat(read(path))
                .as("fragment %s in %s", fragment, file)
                .contains("th:fragment=\"" + fragment + "\"");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
