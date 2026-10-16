package com.qqmu.jync;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards that catch Chinese text leaking into the English UI.
 *
 * <p>Every English-locale view test in this project asserts the rendered page contains no
 * {@code ??key??}, which catches a message key that exists in only one of the two bundles. It
 * structurally cannot catch the other way Chinese reaches an English page: a string hardcoded in
 * Java or a template. Such a literal needs no lookup, so it resolves perfectly and arrives at the
 * user untranslated — this is how {@code AiProtocol} shipped "OpenAI 兼容" and
 * {@code DatabaseType} shipped "自定义 Custom" into the English UI for several releases.
 *
 * <p>The two checks here are deliberately complementary. {@link #findChinese(String)} looks at
 * rendered markup, which cannot see an inline i18n dictionary because that lives inside a
 * {@code <script>} block; {@link #findChineseValue(String)} looks at the English bundle itself,
 * which is where such a dictionary gets its strings. Neither alone is sufficient.
 */
public final class EnglishRenderingSupport {

    private EnglishRenderingSupport() {
    }

    /** CJK ideographs. Deliberately not punctuation or full-width forms: text like "Ollama（本地）"
     * is caught by its ideographs, and a stray full-width parenthesis is not worth failing a build. */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    /**
     * The first run of Chinese text a user would actually see in {@code html}, with a little
     * context, or null if there is none.
     *
     * <p>Returning the offending text rather than a boolean is what makes a failure actionable:
     * "expected false but was true" says nothing about where the leak is.
     *
     * <p>Three regions are stripped first, none of which is a string this project translates:
     * script and style bodies, comment bodies, and the release-notes block. This codebase is
     * written in Chinese, so its JS and markup carry Chinese developer comments by convention, and
     * those ship to the browser without ever reaching the screen — failing on them would mean
     * deleting the notes rather than translating anything. The release notes are publisher-authored
     * prose (see {@link #stripReleaseNotes}), written for a Chinese-first audience and rendered
     * verbatim, so they are data rather than an untranslated UI string.
     */
    public static String findChinese(String html) {
        if (html == null) {
            return null;
        }
        String visible = stripReleaseNotes(html)
                .replaceAll("(?is)<script\\b.*?</script>", " ")
                .replaceAll("(?is)<style\\b.*?</style>", " ")
                .replaceAll("(?s)<!--.*?-->", " ");
        Matcher m = CJK.matcher(visible);
        if (!m.find()) {
            return null;
        }
        return visible.substring(Math.max(0, m.start() - 80),
                Math.min(visible.length(), m.end() + 80));
    }

    /** The one container on /settings that holds text this project does not translate. */
    private static final String RELEASE_NOTES_MARKER = "class=\"release-notes\"";

    /**
     * Removes every {@code <div class="release-notes">} element and its content.
     *
     * <p>That block is the release notes, filled by {@code VersionService} from the notes bundled
     * out of {@code docs/release-notes/} (or from the release body on the forge) and injected with
     * {@code th:utext}. It is authored prose in the publisher's language, not a message key, so no
     * amount of i18n work in this codebase would translate it; an English user still sees Chinese
     * there until English notes are written. That is a documentation gap, and the sweep has nothing
     * to say about it — what it must keep saying is that the rest of the page, which is all message
     * keys, is English.
     *
     * <p>Deliberately a container rather than a URL or a page: exempting /settings would silence
     * the sweep on the app's densest page, and the next hardcoded Chinese literal to land there
     * would ship unnoticed — the exact failure this guard exists to prevent.
     *
     * <p>Divs are matched by counting, so nested markup inside the block cannot end the skip early.
     */
    private static String stripReleaseNotes(String html) {
        StringBuilder kept = new StringBuilder();
        int pos = 0;
        while (true) {
            int marker = html.indexOf(RELEASE_NOTES_MARKER, pos);
            if (marker < 0) {
                break;
            }
            int open = html.lastIndexOf("<div", marker);
            if (open < 0) {
                break;
            }
            kept.append(html, pos, open);
            pos = afterMatchingDiv(html, open);
        }
        return kept.append(html, pos, html.length()).toString();
    }

    /** Index just past the {@code </div>} closing the div opened at {@code open}, or the end of
     *  the markup when the tags do not balance. */
    private static int afterMatchingDiv(String html, int open) {
        int depth = 0;
        int i = open;
        while (i < html.length()) {
            int nextOpen = html.indexOf("<div", i);
            int nextClose = html.indexOf("</div>", i);
            if (nextClose < 0) {
                return html.length();
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                i = nextOpen + 4;
            } else {
                i = nextClose + 6;
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return html.length();
    }

    /**
     * The first Chinese <em>value</em> in a {@code .properties} bundle, as {@code key=value}, or
     * null if there is none. Comment lines ({@code #}) are skipped: the bundles document
     * themselves in the project's own language, and that never reaches a user.
     *
     * <p>Applied to the English bundle this catches the leak the markup check cannot see — the
     * strings an inline i18n dictionary hands to JS.
     */
    public static String findChineseValue(String propertiesBody) {
        if (propertiesBody == null) {
            return null;
        }
        for (String line : propertiesBody.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String value = trimmed.substring(eq + 1);
            if (CJK.matcher(value).find()) {
                return trimmed;
            }
        }
        return null;
    }
}
