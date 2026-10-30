package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dashboard's activity feed runs as a ticker: the top row fades out, the row under it rises
 * into its place, and the list keeps cycling through the newest entries.
 *
 * <p>It used to crawl instead — {@code scrollTop} bumped by one row every 4s, then snapped back
 * to zero — which reads as "the list jumped backwards" rather than "the feed is alive". The two
 * halves of the replacement are pinned here because each is invisible when the other is missing:
 * a row that moves without fading looks like a glitch, and a row that fades without moving never
 * leaves the screen. The browser-side check (rows actually rotate, and stay rotated) lives in
 * the manual pass; this one holds the contract the stylesheet and the script share.
 */
class DashboardActivityMarqueeTest {

    private static final Path JS = Path.of("src/main/resources/static/js/app.js");
    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    @DisplayName("离场的首行在位移的同时淡出")
    void theLeavingRowFadesOut() throws Exception {
        String css = read(CSS);

        String leaving = ruleBody(css, ".feed-live li.is-leaving");
        assertThat(leaving).as("首行淡出必须有这一条规则").isNotNull();
        assertThat(leaving).contains("opacity: 0");
        // 过渡而非动画：动画跑完会自己弹回 opacity:1，行在移动到一半就复活了。
        // 时长写在 li 的基础规则上，挪到末尾时借着同一条过渡淡回来。
        assertThat(ruleBody(css, ".feed-live li {"))
                .as("淡出要靠过渡，不能靠动画").contains("transition: opacity");
    }

    @Test
    @DisplayName("活动流的可视高度不再由条目自己撑开，否则永远没有「下面还有」的部分可轮播")
    void theFeedBodyIsCapped() throws Exception {
        String css = read(CSS);

        // 之前 .feed-card 是 height:100% + 网格拉伸，卡片高度由最长的一列决定；
        // 10 条活动把卡片撑到比左列还高，列表正好铺满，scrollHeight == clientHeight，
        // 轮播的前置条件永远不成立。现在卡片按自身内容收缩，列表只占固定高度。
        assertThat(ruleBody(css, ".feed-body {")).as("列表窗口要有上限").contains("max-height");
        assertThat(ruleBody(css, ".feed-card {"))
                .as("卡片按内容收缩，不再被整行拉高").contains("align-self: start");
    }

    @Test
    @DisplayName("整列靠 transform 平移，过渡时长与脚本一致")
    void theListSlidesByTransform() throws Exception {
        String css = read(CSS);

        String list = ruleBody(css, ".feed-live {");
        assertThat(list).as("列表需要一条能过渡 transform 的规则").isNotNull();
        assertThat(list).contains("transform");
    }

    @Test
    @DisplayName("轮播把首行挪到末尾，不再倒着滚回顶部")
    void theFeedRotatesInsteadOfRewinding() throws Exception {
        String body = functionBody(read(JS), "function bindActivityFeed(");

        assertThat(body).contains("is-leaving");
        assertThat(body).as("首行处理完要挪到列表末尾，形成循环").contains("appendChild");
        // 整列上移时底部会空出一行；超出的部分不足一行时（最后一条只露一点）
        // 那一行底下没有别的条目接着，动画中途会露出白底。垫一份首行的副本在末尾，
        // 移完再连副本一起撤掉。
        assertThat(body).as("上移期间末尾要垫一份首行").contains("cloneNode");
        assertThat(body).as("垫的那份不重复朗读").contains("aria-hidden");
        assertThat(body)
                .as("滚回 scrollTop=0 会让整列倒退一屏，正是这次要换掉的动作")
                .doesNotContain("scrollTop = 0");
    }

    @Test
    @DisplayName("条目没变就不整段替换，免得每 10 秒把轮播顺序打回服务端顺序")
    void refreshKeepsTheOrderWhenNothingIsNew() throws Exception {
        String body = functionBody(read(JS), "function bindActivityFeed(");

        // 轮播把首行挪到末尾，看得见的顺序已经不是服务端顺序了。片段每 10 秒拉一次，
        // 内容还是那 10 条；照旧 innerHTML 换一遍，画面就会从「第 4 条在最上面」
        // 一下跳回「第 1 条在最上面」，而且每 10 秒跳一次。只有真来了新条目才换。
        assertThat(body).as("要先比对 id 再决定换不换").contains("nothingNew");
        assertThat(body).as("没新条目直接返回").contains("if (nothingNew(known, freshIds)) return;");
    }

    @Test
    @DisplayName("轮播到一半才返回的刷新结果直接丢弃，不让整段列表当场跳回顶部")
    void refreshDropsAResponseThatLandsMidRotation() throws Exception {
        String body = functionBody(read(JS), "function bindActivityFeed(");

        // 发起请求前那处挡的是「轮播已经开始」；这里挡的是「请求在途时才轮到这一行」。
        // 少了这一处，innerHTML 会在动画中间换掉整段 ul，transform 跟着消失，
        // 画面从「第二行升上来」直接跳回第一行。
        assertThat(count(body, "if (rotating) return;"))
                .as("响应处理也要挡着轮播").isEqualTo(1);
        assertThat(body).as("发起前的守卫仍在").contains("if (inFlight || rotating) return;");
    }

    private static int count(String text, String needle) {
        int hits = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            hits++;
        }
        return hits;
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Balanced braces starting at the {@code {} that follows {@code marker}, or null. */
    private static String ruleBody(String text, String marker) {
        int at = text.indexOf(marker);
        if (at < 0) {
            return null;
        }
        return braces(text, text.indexOf('{', at));
    }

    private static String functionBody(String text, String marker) {
        int at = text.indexOf(marker);
        assertThat(at).as("找不到 %s", marker).isNotNegative();
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
