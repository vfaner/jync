package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 保存成功后，对象选择面板自己呈现「已保存」的样子，不需要用户再刷新一次。
 *
 * <p>详情页是按服务端渲染的已保存选择排版的：勾选的对象排在最前面
 * （{@code ProjectService.selectedFirst}），目标表名只保留真正的重命名
 * （{@code normalizeTableNameMapping}）。改走 AJAX 保存之后页面不再重渲染，这两条
 * 规则就必须在浏览器里补上，否则用户点完保存看到的还是改动前的顺序与输入值，
 * 只能手动刷新才「生效」——和没保存没有区别。
 *
 * <p>效果本身在浏览器里，这里钉住的是脚本与这两条服务端规则的契约：保存成功才重排、
 * 重排只移动已有节点（勾选与输入框内容不能丢）、组内保持原顺序（与服务端两次稳定
 * 分区一致），并且不在用户点勾选的过程中重排（行会在光标下跳动）。
 */
class ProjectObjectPickerRefreshTest {

    private static final Path JS = Path.of("src/main/resources/static/js/app.js");

    @Test
    @DisplayName("保存成功后重排：勾选的行在前，组内保持原顺序")
    void aSuccessfulSaveReordersThePickers() throws Exception {
        String saved = functionBody(read(JS), "function applySavedConfig(");
        assertThat(saved).as("保存成功就要按服务端排版刷新面板，否则只能整页刷新")
                .contains("reorderPickers()");

        String reorder = functionBody(read(JS), "function reorderPickers(");
        assertThat(reorder).as("三个选择器都按 data-role 找").contains("[data-role=\"picker\"]");
        assertThat(reorder).as("只重排列表内的行").contains("picker-list");
        assertThat(reorder).as("移动现有节点而不是重建：勾选、输入内容与监听器都要保留")
                .contains("appendChild");
        assertThat(reorder).as("勾选在前、其余在后").contains("isPicked");
        assertThat(reorder).as("列表是内部滚动区：顺序变了要把视图带回顶部")
                .contains("list.scrollTop = 0");
        assertThat(functionBody(read(JS), "function isPicked("))
                .as("勾选状态的唯一判据").contains("checked");
    }

    @Test
    @DisplayName("点勾选时不许重排：行会在光标下跳动")
    void checkingABoxDoesNotReorderTheRows() throws Exception {
        String picker = functionBody(read(JS), "function bindPicker(");
        assertThat(picker).as("重排只属于保存成功，不属于 change 刷新")
                .doesNotContain("reorderPickers");
    }

    @Test
    @DisplayName("目标表名回落到服务端会保存的值：未勾选与同名映射留空")
    void savedMappingsShowWhatTheServerActuallyKept() throws Exception {
        String reorder = functionBody(read(JS), "function reorderPickers(");
        assertThat(reorder).contains("normalizeSavedMappings");

        String normalize = functionBody(read(JS), "function normalizeSavedMappings(");
        assertThat(normalize).as("源表名在 data-source 上").contains("data-source");
        assertThat(normalize).as("同名映射服务端不保存，页面回落到占位符")
                .contains("=== source");
        assertThat(normalize).as("未勾选的行同样回落（它不提交映射）").contains("isPicked");
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
