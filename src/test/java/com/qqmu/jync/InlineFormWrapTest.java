package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The system-log filter row is one flex line of four controls: the log file, the level, a keyword
 * input and the search button. Each of the two inputs asks for ~220px, so the row is some 660px
 * wide; on a 390px phone the card's {@code overflow: hidden} cut the tail off and the Search
 * button could not be reached at all — no horizontal scroll, it was simply gone.
 *
 * <p>Wrapping is the fix, with the same margin trick the action rows use: spacing is applied to
 * every child rather than to "every child but the first", so a control that starts a wrapped line
 * does not inherit a leading indent.
 */
class InlineFormWrapTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    @DisplayName("窄屏下筛选行折行，最后一个按钮不会被卡片裁掉")
    void theFilterRowWrapsOnANarrowScreen() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        String block = mediaBlock(css, "@media (max-width: 640px)", ".inline-form");

        assertThat(block).as("筛选行必须有一条窄屏规则").isNotNull();
        assertThat(block).as("不换行时超出的控件被卡片 overflow:hidden 吞掉，滚都滚不到")
                .contains("flex-wrap: wrap");
    }

    @Test
    @DisplayName("间距加在每个子项上，折行后首项不带缩进")
    void spacingAppliesToEveryChild() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        String spacing = ruleBody(css, ".inline-form > * {");

        assertThat(spacing).as("折行后要靠每条子项自己的外边距排间距").isNotNull();
        assertThat(spacing).contains("margin-right");
        // 上一版的 > * + * { margin-left } 只给后继项加左边距，折行后每行第一个自带缩进。
        assertThat(css).as("旧的选择器已经过时").doesNotContain(".inline-form > * + *");
    }

    /** The body of the media block that also mentions {@code marker}, or null when none does. */
    private static String mediaBlock(String css, String header, String marker) {
        int from = 0;
        while ((from = css.indexOf(header, from)) >= 0) {
            int open = css.indexOf('{', from);
            if (open < 0) {
                return null;
            }
            int depth = 0;
            int i = open;
            for (; i < css.length(); i++) {
                if (css.charAt(i) == '{') {
                    depth++;
                } else if (css.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            String body = css.substring(open, Math.min(i + 1, css.length()));
            if (body.contains(marker)) {
                return body;
            }
            from = i + 1;
        }
        return null;
    }

    /** Balanced braces starting at the {@code {} that follows {@code marker}, or null. */
    private static String ruleBody(String text, String marker) {
        int at = text.indexOf(marker);
        if (at < 0) {
            return null;
        }
        return braces(text, text.indexOf('{', at));
    }

    private static String braces(String text, int open) {
        if (open < 0) {
            return null;
        }
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
