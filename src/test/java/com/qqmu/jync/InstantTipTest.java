package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The row-level warning icons carry a {@code data-tip}; app.js paints it at once.
 *
 * <p>The alternative — a {@code title} attribute or an SVG {@code <title>} child — leaves the
 * delay and the styling to the browser: roughly a second of hovering before anything shows,
 * and a system-grey box instead of the app's dark bubble. A CSS-only bubble is not an option
 * either: the icon sits inside {@code .tbl-wrap} (overflow-x: auto) inside {@code .card}
 * (overflow: hidden), so an absolutely positioned bubble is clipped. Hence a fixed bubble
 * attached to the body, shown on hover and on keyboard focus with no timer in between.
 */
class InstantTipTest {

    private static final Path JS = Path.of("src/main/resources/static/js/app.js");
    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    @DisplayName("悬停/聚焦立即弹出，不用定时器拖延")
    void theTipShowsOnHoverAndFocusWithoutATimer() throws Exception {
        String body = functionBody(read(JS), "function bindInstantTips(");

        assertThat(body).contains("'mouseenter'");
        assertThat(body).as("键盘用户也要能看到，不能只认鼠标")
                .contains("'focus'");
        assertThat(body).as("延迟正是要摆脱的东西").doesNotContain("setTimeout");
    }

    @Test
    @DisplayName("气泡固定挂在 body 上：表格与卡片的 overflow 裁不到它")
    void theBubbleEscapesTheTablesClipping() throws Exception {
        assertThat(read(JS))
                .as("复用 .tip-body 的深底浅字，只换成 fixed 定位")
                .contains("'tip-body tip-float'");

        String css = read(CSS);
        String rule = cssRule(css, ".tip-body.tip-float {");
        assertThat(rule).contains("position: fixed");
        // 绝对定位会被 .tbl-wrap / .card 的 overflow 裁掉 —— 这正是 fixed 的由来。
        assertThat(rule).doesNotContain("position: absolute");
        assertThat(css).contains(".tip-body.tip-float.is-on");
    }

    @Test
    @DisplayName("页面加载时注册，否则 data-tip 只是一段死属性")
    void itIsRegisteredAtStartup() throws Exception {
        assertThat(read(JS)).contains("bindInstantTips();");
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String cssRule(String css, String selector) {
        int at = css.indexOf(selector);
        assertThat(at).as("找不到 %s", selector).isNotNegative();
        int open = css.indexOf('{', at);
        int close = css.indexOf('}', open);
        return css.substring(open, close + 1);
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
