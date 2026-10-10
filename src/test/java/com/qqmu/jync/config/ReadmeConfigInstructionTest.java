package com.qqmu.jync.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the external-config launch instruction in both READMEs.
 *
 * <p>The regression this exists for (issue #IKKQ4J, seen on every release since v1.0.0):
 * the READMEs told production users to start with {@code --spring.config.location=file:./application.yml}.
 * That flag <em>replaces</em> all default config locations, so the jar-internal
 * {@code application.yml} is never read. The documented sample does not carry the internal
 * keys, and the deployment breaks in three distinct ways:
 * <ul>
 *   <li>{@code app.github-url} is unresolved → startup fails outright;</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: update} is lost, and file-based H2 does not
 *       count as embedded, so the default is {@code none} → fresh installs create no tables
 *       ("Table PROJECT not found (this database is empty)");</li>
 *   <li>{@code spring.messages.basename: i18n/messages} is lost → the basename falls back to
 *       {@code messages}, no bundle resolves, and every label renders as
 *       {@code ??login.title_zh_CN??} — the reported "乱码" login page.</li>
 * </ul>
 * All three were reproduced empirically against the released v1.4.0 jar;
 * {@code --spring.config.additional-location} (additive overlay) with the pristine README
 * sample renders the login page correctly and is what the docs must keep instructing.
 */
class ReadmeConfigInstructionTest {

    @Test
    void bothReadmesUseTheAdditiveConfigFlag() throws IOException {
        for (String name : new String[]{"README.md", "README_EN.md"}) {
            String readme = Files.readString(Path.of(name), StandardCharsets.UTF_8);
            assertThat(readme)
                    .as("%s must launch with the additive additional-location flag", name)
                    .contains("--spring.config.additional-location=file:");
            assertThat(readme)
                    .as("%s must never instruct the replacing spring.config.location form: "
                            + "it drops the jar-internal application.yml (i18n basename, ddl-auto, "
                            + "app.github-url) and breaks login-page rendering and fresh installs", name)
                    .doesNotContain("--spring.config.location=");
        }
    }
}
