package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The performance page draws the same samples as the dashboard, through the same Catmull-Rom
 * helper. A spline borrows its tangents from neighbouring samples, so a run of zero samples
 * before a peak sags below zero and a flat top bulges above the highest sample; the chart
 * would then claim activity that never happened.
 *
 * <p>The change-type donut is fed enum names from the server (INSERT, UPDATE, DELETE) while
 * every other change-type label is translated through {@code changeType.*}; the legend must
 * take its words from the page instead of printing the code.
 */
class PerformanceChartContractTest {

    private static final Path JS = Path.of("src/main/resources/static/js/metrics.js");
    private static final Path TEMPLATE =
            Path.of("src/main/resources/templates/project-performance.html");

    @Test
    @DisplayName("性能折线走与看板同一份夹取过的平滑曲线，不另写一套")
    void thePerformanceCurveStaysBetweenTheSamplesItJoins() throws Exception {
        String body = functionBody(read(JS), "function flushSegment(");

        assertThat(body)
                .as("分段绘制必须复用 charts.js 的 smoothPath（其夹取契约由看板测试守住）")
                .contains("smoothPath(segment)")
                .doesNotContain("c1y =");
    }

    @Test
    @DisplayName("孤立采样点也要画出标记，不能因为连不成线就让整张图看起来没有数据")
    void isolatedSamplesAreDrawnAsMarkers() throws Exception {
        // 一次手动同步的两个采样点落在同一个小时桶里，行数序列只有一个非空桶。折线
        // 至少要两个点才画得出来，孤立点如果只走折线路径，整张图就是空的——卡片上有
        // 数字、图里却没有，看上去像采集失败。
        String body = functionBody(read(JS), "function line(");

        assertThat(body)
                .as("要识别前后都没有相邻桶的孤立点")
                .contains("var prev = m > 0 ? coords[m - 1] : null")
                .contains("var next = m < coords.length - 1 ? coords[m + 1] : null");
    }

    @Test
    @DisplayName("变更类型占比图用页面上的译文，不直接显示 INSERT / UPDATE / DELETE")
    void theChangeShareLegendUsesTheTranslatedChangeNames() throws Exception {
        String js = read(JS);
        assertThat(functionBody(js, "function localizedShare("))
                .as("服务端给的是枚举名，译文挂在 data-label-* 上")
                .contains("'data-label-'");

        String page = read(TEMPLATE);
        assertThat(page)
                .as("模板把三种变更类型的译文交给前端")
                .contains("data-label-insert=#{changeType.INSERT}")
                .contains("data-label-update=#{changeType.UPDATE}")
                .contains("data-label-delete=#{changeType.DELETE}");
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String functionBody(String text, String marker) {
        int at = text.indexOf(marker);
        assertThat(at).as("找不到 %s", marker).isNotNegative();
        int open = text.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(open, i + 1);
                }
            }
        }
        return null;
    }
}
