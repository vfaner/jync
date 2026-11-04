package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dashboard trend is an hourly count: it cannot be negative and cannot exceed its own
 * maximum. A Catmull-Rom spline borrows its tangents from the neighbouring samples, so a run
 * of zero hours before a peak sags below the zero line, and a flat top bulges above the
 * highest sample — the chart then claims changes that never happened.
 *
 * <p>曲线 helper 现由看板与性能页共用（charts.js）；这里守住它自身的夹取契约，浏览器侧
 * 的目视检查（曲线确实落在 0 与轴顶之间）仍在手工回归里做。
 */
class DashboardActivityChartCurveTest {

    private static final Path JS = Path.of("src/main/resources/static/js/charts.js");

    @Test
    @DisplayName("平滑曲线的控制点夹在本段取值区间内，不会画到 0 以下或冲过最高点")
    void theCurveStaysBetweenTheSamplesItJoins() throws Exception {
        String body = functionBody(read(JS), "function smoothPath(");

        assertThat(body)
                .as("下控制点要夹进本段的取值区间")
                .contains("c1y = clampY(");
        assertThat(body)
                .as("上控制点也要夹（下降段和平顶段都会过冲）")
                .contains("c2y = clampY(");
        assertThat(body)
                .as("夹取区间就是本段两端的 y")
                .contains("Math.min(p1.y, p2.y)")
                .contains("Math.max(p1.y, p2.y)");
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
