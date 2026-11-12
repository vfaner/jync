package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 模板里引用的排版工具类（{@code flex}、{@code grid}、{@code mb-4}…）在 app.css 里都有定义。
 *
 * <p>漏定义不会抛异常：元素按默认盒模型渲染，内联的 {@code grid-template-columns}
 * 因为没有 {@code display:grid} 而完全不生效，页面只是“看起来有点怪”。ETL 预检卡片
 * 就这样带着 {@code class="grid"} 发布过——两栏被排成了上下两段，检查列表和结果列
 * 各自挤成一列，谁都没发现。
 *
 * <p>只校验排版这一族类名：{@code contact-link}、{@code is-unknown} 这类是语义钩子，
 * 由父级或状态规则着色，本来就不需要自己的定义。
 */
class TemplateUtilityClassCoverageTest {

    private static final String CSS = "static/css/app.css";
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern CLASS_ATTR = Pattern.compile("\\bclass=\"([^\"]*)\"");
    private static final Pattern TH_CLASS = Pattern.compile("th:class(?:append)?=\"([^\"]*)\"");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^']*)'");
    /** 显示与间距工具类：名字就承诺了效果，缺定义即布局失真。 */
    private static final Pattern UTILITY =
            Pattern.compile("flex|inline-flex|grid|justify-[\\w-]+|items-[\\w-]+|gap-[\\w-]+|[mp][trblxy]?-\\d+");

    @Test
    @DisplayName("every display/spacing utility used in a template is defined in app.css")
    void everyUtilityClassIsDefined() throws Exception {
        Set<String> defined = cssClasses();
        Map<String, Set<String>> usedBy = utilitiesByTemplate();

        Map<String, Set<String>> unresolved = new TreeMap<>();
        usedBy.forEach((template, names) -> {
            Set<String> missing = new TreeSet<>(names);
            missing.removeAll(defined);
            if (!missing.isEmpty()) {
                unresolved.put(template, missing);
            }
        });

        assertThat(unresolved)
                .as("these utility classes are used in templates but never defined in %s, so the "
                        + "element renders with the default box model: %s", CSS, defined)
                .isEmpty();
    }

    /**
     * A scan that finds nothing passes trivially; a typo in the pattern or a moved stylesheet
     * would look like success. This pins that both sides are populated.
     */
    @Test
    @DisplayName("the scan reads both the stylesheet and the templates")
    void theScanIsNotVacuous() throws Exception {
        assertThat(cssClasses()).as("classes parsed from %s", CSS).contains("small", "muted", "badge");
        assertThat(utilitiesByTemplate().values().stream().flatMap(Set::stream))
                .as("utility classes referenced across all templates")
                .contains("flex", "grid", "justify-between");
    }

    private static Set<String> cssClasses() throws Exception {
        String css = COMMENT.matcher(read(CSS)).replaceAll("");
        Set<String> classes = new LinkedHashSet<>();
        Matcher block = Pattern.compile("([^{}]+)\\{").matcher(css);
        while (block.find()) {
            String selector = block.group(1).trim();
            if (selector.startsWith("@")) {
                continue;
            }
            Matcher cls = Pattern.compile("\\.([A-Za-z_][\\w-]*)").matcher(selector);
            while (cls.find()) {
                classes.add(cls.group(1));
            }
        }
        return classes;
    }

    /** Template filename → the display/spacing utility classes it references, in file order. */
    private static Map<String, Set<String>> utilitiesByTemplate() throws Exception {
        Map<String, Set<String>> usedBy = new TreeMap<>();
        Resource[] templates = new PathMatchingResourcePatternResolver()
                // ** covers templates/*.html and templates/fragments/*.html alike.
                .getResources("classpath:templates/**/*.html");
        for (Resource template : templates) {
            String body = COMMENT.matcher(new String(template.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8)).replaceAll("");
            Set<String> utilities = new LinkedHashSet<>();
            Matcher attr = CLASS_ATTR.matcher(body);
            while (attr.find()) {
                collect(attr.group(1), utilities);
            }
            // th:classappend="'badge' + (x ? 'badge-ok' : 'badge-danger')"：类名在单引号里。
            Matcher dyn = TH_CLASS.matcher(body);
            while (dyn.find()) {
                Matcher literal = SINGLE_QUOTED.matcher(dyn.group(1));
                while (literal.find()) {
                    collect(literal.group(1), utilities);
                }
            }
            if (!utilities.isEmpty()) {
                // 裸文件名会撞车（etl-form/precheck.html 与 migration-form/precheck.html）。
                usedBy.put(template.getDescription().replaceAll("^.*\\[", "").replaceAll("\\]$", ""),
                        utilities);
            }
        }
        return usedBy;
    }

    private static void collect(String classList, Set<String> into) {
        for (String token : classList.trim().split("\\s+")) {
            if (!token.isEmpty() && UTILITY.matcher(token).matches()) {
                into.add(token);
            }
        }
    }

    private static String read(String classpathResource) throws Exception {
        return new String(new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + classpathResource).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
