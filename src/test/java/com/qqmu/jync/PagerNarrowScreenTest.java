package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pager bar keeps everything on one line and hugs the right edge. That works until the row
 * is wider than the phone: with {@code justify-content: flex-end} and {@code overflow-x: auto},
 * the excess runs off the <em>left</em> edge, and the scroll range only covers the right, so the
 * first item is clipped and cannot be scrolled into view. In the English UI at 390px the total
 * rendered as "tal" — the head of "3 in total" was simply gone.
 *
 * <p>Below 640px the bar therefore wraps instead of overflowing. This test pins the rule; the
 * browser pass covers what the stylesheet alone cannot say.
 */
class PagerNarrowScreenTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    @DisplayName("窄屏下分页条折行，靠右超出左边界被吞掉的那段不再存在")
    void theBarWrapsOnANarrowScreen() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        String block = mediaBlock(css, "@media (max-width: 640px)", ".pager-bar");

        assertThat(block).as("窄屏分页条必须有一条自己的规则").isNotNull();
        assertThat(block).as("靠右对齐 + 不换行，超出的部分会跑到左边且滚不到").contains("flex-wrap: wrap");
        // 折行后每行长度不一，居中比贴右更整齐；贴右会让第二行的「前往 N 页」孤零零挂在右边。
        assertThat(block).contains("justify-content: center");
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
}
