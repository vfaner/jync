package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every message key that can reach the browser is resolvable by the browser.
 *
 * <p>JSON endpoints answer with an i18n <em>key</em>, not text: {@code app.js} runs it through
 * {@code t()}, which looks the key up in {@code window.JyncMessages} and — when it is absent —
 * falls back to returning the key itself. Nothing renders {@code ??key??}, so the leak is silent
 * until a user reports "it showed an English error": the reset button refused with
 * {@code error.project.sync.in.progress} because that key was in the bundles but not in the map.
 *
 * <p>Three checks, each aimed at one way the map drifts:
 * <ol>
 *   <li>a reference in the map that neither bundle defines (the page renders {@code ??key??}),
 *   <li>a key a REST controller returns that was never added to the map,
 *   <li>a refusal the project-control buttons can receive that was never added to the map.
 * </ol>
 */
class JsMessageMapCoverageTest {

    private static final Path LAYOUT = Path.of("src/main/resources/templates/layout.html");

    private static final List<Path> JSON_CONTROLLERS = List.of(
            Path.of("src/main/java/com/qqmu/jync/controller/api/ProjectApiController.java"),
            Path.of("src/main/java/com/qqmu/jync/controller/CdcApiController.java"));

    /**
     * Refusals reachable from the buttons backed by the two controllers above.
     *
     * <p>Curated rather than scraped: {@code ProjectService} also throws keys that only ever
     * render server-side (a redirect flash, a validation page), and dragging those into the map
     * would grow every page for nothing. The cost is that a new refusal must be listed here —
     * which is the point: someone has to decide it can reach a toast.
     */
    private static final List<String> CONTROL_REFUSALS = List.of(
            "error.project.not.found",
            "error.project.stop.before.reset",
            "error.project.sync.in.progress",
            "error.project.lock.not.held",
            "error.project.cdc.use.rebaseline",
            "error.project.cdc.reset.use.rebaseline",
            "error.project.cdc.not.cdc",
            "error.project.no.objects",
            "error.project.no.options",
            "cdc.rebaseline.not.holder",
            "error.unexpected");

    @Test
    @DisplayName("every key the map references exists in both bundles")
    void mapReferencesExistInBothBundles() throws Exception {
        Set<String> refs = referencedKeys();
        assertThat(refs).isNotEmpty();

        Set<String> en = I18nBundleParityTest.keysOf("i18n/messages.properties");
        Set<String> zh = I18nBundleParityTest.keysOf("i18n/messages_zh_CN.properties");

        assertThat(missing(refs, en))
                .as("layout.html references these keys but messages.properties has no value")
                .isEmpty();
        assertThat(missing(refs, zh))
                .as("layout.html references these keys but messages_zh_CN.properties has no value")
                .isEmpty();
    }

    @Test
    @DisplayName("every key the JSON controllers return is in the browser map")
    void controllerKeysAreInTheMap() throws Exception {
        Set<String> map = mapKeys();

        Set<String> returned = new TreeSet<>();
        for (Path controller : JSON_CONTROLLERS) {
            String source = Files.readString(controller, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"((?:msg|error|warn|cdc)\\.[A-Za-z0-9_.]+)\"")
                    .matcher(source);
            while (m.find()) {
                returned.add(m.group(1));
            }
        }

        assertThat(returned).as("sanity: the controllers do return literal keys").isNotEmpty();
        assertThat(missing(returned, map))
                .as("these keys reach app.js's t() and would be shown as the raw key")
                .isEmpty();
    }

    @Test
    @DisplayName("every refusal the project buttons can show is in the browser map")
    void controlRefusalsAreInTheMap() throws Exception {
        assertThat(missing(new TreeSet<>(CONTROL_REFUSALS), mapKeys()))
                .as("the button shows the raw key instead of this sentence")
                .isEmpty();
    }

    private static Set<String> mapKeys() throws Exception {
        Matcher key = Pattern.compile("'([^']+)'\\s*:").matcher(mapBlock());
        Set<String> keys = new TreeSet<>();
        while (key.find()) {
            keys.add(key.group(1));
        }
        return keys;
    }

    private static Set<String> referencedKeys() throws Exception {
        Matcher ref = Pattern.compile("#\\{([^}]+)}").matcher(mapBlock());
        Set<String> keys = new TreeSet<>();
        while (ref.find()) {
            keys.add(ref.group(1));
        }
        return keys;
    }

    /** The {@code window.JyncMessages = { ... }} body; asserting it non-empty guards the parse. */
    private static String mapBlock() throws Exception {
        String html = Files.readString(LAYOUT, StandardCharsets.UTF_8);
        Matcher block = Pattern.compile(
                "window\\.JyncMessages\\s*=\\s*\\{(.*?)\\n\\s*};", Pattern.DOTALL).matcher(html);
        assertThat(block.find()).as("layout.html defines window.JyncMessages").isTrue();
        return block.group(1);
    }

    private static Set<String> missing(Set<String> wanted, Set<String> present) {
        Set<String> diff = new TreeSet<>(wanted);
        diff.removeAll(present);
        return diff;
    }
}
