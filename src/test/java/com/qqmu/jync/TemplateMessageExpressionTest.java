package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.ExpressionContext;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.standard.expression.IStandardExpressionParser;
import org.thymeleaf.standard.expression.StandardExpressions;

/**
 * Parses every {@code #{...}} message expression in the templates. Thymeleaf reports a bad
 * message expression as "Could not parse as expression" only when the containing element is
 * rendered, so a branch that no ordinary unit test happens to render can ship a broken page.
 * Parsing the expressions directly catches that class of failure without evaluating variables.
 */
class TemplateMessageExpressionTest {

    @Test
    @DisplayName("every template message expression parses")
    void everyMessageExpressionParses() throws Exception {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        IStandardExpressionParser parser = StandardExpressions.getExpressionParser(
                engine.getConfiguration());
        ExpressionContext context = new ExpressionContext(engine.getConfiguration());

        List<String> failures = new ArrayList<>();
        List<Path> templates;
        try (Stream<Path> walk = Files.walk(Path.of("src/main/resources/templates"))) {
            templates = walk.filter(p -> p.toString().endsWith(".html")).toList();
        }
        for (Path template : templates) {
            String text = Files.readString(template, StandardCharsets.UTF_8);
            for (String expression : messageExpressions(text)) {
                try {
                    parser.parseExpression(context, expression);
                } catch (RuntimeException e) {
                    failures.add(template + ": " + expression + " -> " + e.getMessage());
                }
            }
        }
        assertThat(failures).as("unparseable Thymeleaf message expressions").isEmpty();
    }

    /** Balanced-brace scan; message expressions can contain nested {@code ${...}}. */
    private static List<String> messageExpressions(String text) {
        List<String> expressions = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = text.indexOf("#{", from);
            if (start < 0) {
                return expressions;
            }
            int depth = 0;
            int end = -1;
            for (int i = start + 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (end < 0) {
                return expressions;                 // malformed template, another test's job
            }
            expressions.add(text.substring(start, end + 1));
            from = end + 1;
        }
    }
}
