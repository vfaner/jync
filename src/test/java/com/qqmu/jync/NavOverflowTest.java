package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The top bar is one flex row: logo, six nav entries and the language / theme / donate / user
 * controls. In the English UI that row needs about 1300px — the logo tagline alone is 250px — so
 * on a 1280 or 1024 laptop the right-hand controls were pushed past the edge, the user menu was
 * clipped, and the whole page scrolled sideways.
 *
 * <p>Between the drawer breakpoint (900px) and the width where the row actually fits, the bar
 * therefore sheds its decorative weight instead of overflowing: the tagline goes, and gaps and
 * paddings tighten. The rest of the page keeps its layout — only the bar is affected.
 */
class NavOverflowTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    @DisplayName("901–1330px 之间导航收密度：去掉 logo 副标题、收紧间距")
    void theBarShedsWeightBeforeItOverflows() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        String block = mediaBlock(css, "@media (max-width: 1330px) and (min-width: 901px)", ".nav-inner");

        assertThat(block).as("这一段必须有它自己的导航规则").isNotNull();
        assertThat(block)
                .as("250px 的副标题是整条导航里最占地方的一截，应先让它退场")
                .contains(".logo-text small { display: none; }");
        assertThat(block).as("间距同时收一收").contains("gap: 16px");
    }

    @Test
    @DisplayName("再窄一档：字号降一级、角色小标退场，用户菜单仍旧留在屏幕内")
    void theTightBandDropsTheRolePill() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        String block = mediaBlock(css, "@media (max-width: 1040px) and (min-width: 901px)", ".nav-inner");

        assertThat(block).as("这一档要有自己的规则").isNotNull();
        assertThat(block).contains(".user-chip .role { display: none; }");
        assertThat(block).contains("font-size: 13px");
    }

    @Test
    @DisplayName("最后一档：品牌名退场，只留图标块")
    void theNarrowestBandDropsTheWordmark() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        String block = mediaBlock(css, "@media (max-width: 960px) and (min-width: 901px)", ".logo-text");

        assertThat(block).as("这一档要有自己的规则").isNotNull();
        assertThat(block).contains(".logo-text { display: none; }");
    }

    @Test
    @DisplayName("手机宽度下品牌名退场：只剩「Jy…」时不如不显示")
    void theWordmarkLeavesWhenItCannotBeRead() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        String block = mediaBlock(css, "@media (max-width: 430px)", ".logo-text");

        assertThat(block).as("这一档要有自己的规则").isNotNull();
        assertThat(block).as("430px 以下品牌名截成「Jy…」，360px 只剩省略号")
                .contains(".logo-text { display: none; }");
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
