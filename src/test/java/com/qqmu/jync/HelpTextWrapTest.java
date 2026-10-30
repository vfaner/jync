package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Help text under a setting names things like {@code pg_logical_slot_get_changes} — one token
 * longer than the value column on a phone. Nothing there can wrap at a space before the card's
 * {@code overflow: hidden} edge, so the tail of the sentence was simply cut off.
 *
 * <p>Two halves are needed: the paragraph must be allowed to break inside the token, and where it
 * sits in a flex row ({@code .kv.wide dd}) it must also be allowed to shrink below its
 * max-content width, which is what {@code min-width: auto} forbids for flex items.
 */
class HelpTextWrapTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    @DisplayName("说明文字里的长标识符在栏宽处断行，不再整段探出卡片被裁掉")
    void helpTextBreaksInsideLongTokens() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);

        assertThat(ruleBody(css, ".dl dd .help {"))
                .as("说明文字要能和长标识符一起断行").contains("overflow-wrap: anywhere");
    }

    @Test
    @DisplayName("并排成一行的那种说明在 flex 行里也能收缩，否则断行无从谈起")
    void theWideRowLetsTheHelpShrink() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);

        assertThat(ruleBody(css, ".dl.pairs .kv.wide dd .help {"))
                .as("flex 子项默认 min-width:auto，不许缩到最宽的那个词以下")
                .contains("min-width: 0");
    }

    /** Balanced braces starting at the {@code {} that follows {@code marker}, or null. */
    private static String ruleBody(String text, String marker) {
        int at = text.indexOf(marker);
        if (at < 0) {
            return null;
        }
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
