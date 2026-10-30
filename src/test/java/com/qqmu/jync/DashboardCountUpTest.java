package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 看板的数字是滚上去的：首屏从 0 数到真实值，10 秒刷新时从旧值数到新值。
 *
 * <p>效果本身在浏览器里，只能手工过一遍；这里钉住的是脚本与模板之间的契约——计数器
 * 由模板用 {@code data-count} 标出来，滚动逐帧进行、尊重系统的「减少动态效果」，并且
 * 刷新时以旧值为起点。这几点各自缺失时的症状各不相同：没有标记就一个数字都不动；
 * 用定时器会在高刷新率屏上跳格、页面隐藏时照跑；每次从 0 重来则每 10 秒倒回去一次，
 * 比不动更难看。
 */
class DashboardCountUpTest {

    private static final Path JS = Path.of("src/main/resources/static/js/app.js");

    @Test
    @DisplayName("数字逐帧滚动，不靠定时器；开了减少动态效果就直接落定")
    void theCountUpRunsOnAnimationFrames() throws Exception {
        String body = functionBody(read(JS), "function countUp(");

        assertThat(body).contains("requestAnimationFrame");
        assertThat(body)
                .as("定时器在高刷新率屏上会跳格，页面隐藏时还照跑")
                .doesNotContain("setInterval");
        assertThat(body).contains("prefersReducedMotion");
    }

    @Test
    @DisplayName("刷新时以旧值为起点；每次从 0 重来会让数字每 10 秒倒回去一次")
    void thePollStartsFromThePreviousNumber() throws Exception {
        String body = functionBody(read(JS), "function bindLiveStats(");

        assertThat(body).as("换 innerHTML 之前先记下屏幕上的数字")
                .contains("counterValues(grid)");
        assertThat(body).as("首屏从 0 起步").contains("animateCounters(grid, null)");
        assertThat(body).as("刷新从旧值起步").contains("animateCounters(grid, previousValues)");
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
