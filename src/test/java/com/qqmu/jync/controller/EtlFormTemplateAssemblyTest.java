package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * ETL 任务表单按「一段一块」组装：连接、取数来源（SQL / 原生 MongoDB）、目标写入、
 * 调度、上次运行、预检各自成文件，壳模板只保留页头装配与表单骨架。
 *
 * <p>与任务详情页同一套边界：改 Mongo 源的字段摸不到 SQL 源，改调度摸不到预检。
 * 源/目标引擎将来有专属参数时，在 basic 卡片的两个插槽下各加一个
 * {@code source-<库>.html} / {@code target-<库>.html}，其它引擎的模块不动。
 */
class EtlFormTemplateAssemblyTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path FORM = TEMPLATES.resolve("etl-form");

    @Test
    void theShellAssemblesEveryBlockFromADedicatedModule() throws IOException {
        String shell = read(TEMPLATES.resolve("etl-form.html"));

        assertThat(shell)
                .contains("etl-form/head :: pageHead")
                .contains("etl-form/head :: alerts")
                .contains("etl-form/basic :: card")
                .contains("etl-form/schedule :: card")
                .contains("etl-form/last-run :: card")
                .contains("etl-form/precheck :: card");
    }

    @Test
    void theShellKeepsOnlyFormScaffoldingAndNoFieldMarkup() throws IOException {
        String shell = read(TEMPLATES.resolve("etl-form.html"));

        assertThat(shell)
                .contains("<form method=\"post\"")
                .contains("style=\"max-width:960px\"")
                .doesNotContain("name=\"sourceSql\"")
                .doesNotContain("name=\"sourceCollection\"")
                .doesNotContain("name=\"targetTable\"")
                .doesNotContain("name=\"cronExpression\"")
                .doesNotContain("precheck.passed()");
    }

    @Test
    void theBasicCardAssemblesConnectionsSourceAndTargetBlocks() throws IOException {
        String basic = read(FORM.resolve("basic.html"));

        assertThat(basic)
                .contains("etl-form/connections :: block")
                .contains("etl-form/source-sql :: block")
                .contains("etl-form/source-mongo :: block")
                .contains("etl-form/target :: block")
                .contains("etl-form/options :: block")
                .doesNotContain("name=\"sourceSql\"")
                .doesNotContain("name=\"sourceCollection\"")
                .doesNotContain("name=\"targetTable\"");
    }

    @Test
    void theTwoSourceKindsNeverShareAModule() throws IOException {
        assertThat(read(FORM.resolve("source-sql.html")))
                .contains("data-role=\"source-sql-group\"")
                .contains("name=\"sourceSql\"")
                .doesNotContain("name=\"sourceCollection\"");
        assertThat(read(FORM.resolve("source-mongo.html")))
                .contains("data-role=\"source-mongo-group\"")
                .contains("name=\"sourceCollection\"")
                .doesNotContain("name=\"sourceSql\"");
    }

    @Test
    void targetAndScriptOptionsAreSeparateBlocks() throws IOException {
        assertThat(read(FORM.resolve("target.html")))
                .contains("name=\"targetTable\"")
                .contains("name=\"writeMode\"")
                .doesNotContain("name=\"preSql\"");
        assertThat(read(FORM.resolve("options.html")))
                .contains("name=\"preSql\"")
                .contains("name=\"postSql\"")
                .contains("name=\"rangeCondition\"")
                .contains("name=\"resumeKeyColumn\"")
                .doesNotContain("name=\"writeMode\"");
    }

    @Test
    void everyBlockFileDefinesTheFragmentItsParentIncludes() throws IOException {
        assertFragment("head.html", "pageHead");
        assertFragment("head.html", "alerts");
        assertFragment("basic.html", "card");
        assertFragment("connections.html", "block");
        assertFragment("source-sql.html", "block");
        assertFragment("source-mongo.html", "block");
        assertFragment("target.html", "block");
        assertFragment("options.html", "block");
        assertFragment("schedule.html", "card");
        assertFragment("last-run.html", "card");
        assertFragment("precheck.html", "card");
    }

    private static void assertFragment(String file, String fragment) throws IOException {
        Path path = FORM.resolve(file);
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
