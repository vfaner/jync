package com.qqmu.jync.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The templates toggle visibility with the plain {@code hidden} attribute (the upgrade-ready
 * badge, the modal action rows, the connection test-result boxes). The attribute only works
 * through the UA stylesheet's {@code [hidden] { display: none }}, which loses to ANY author
 * display rule — and .badge / .alert / .modal-actions all set display. Without an
 * {@code !important} guard those elements stay visible while "hidden": the amber
 * "restart to apply" badge showed on an up-to-date install because of exactly this.
 */
class HiddenAttributeContractTest {

    @Test
    void hiddenAttributeBeatsEveryClassDisplayRule() throws IOException {
        String css = new String(new ClassPathResource("static/css/app.css")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(css).containsPattern(
                "\\[hidden\\]\\s*\\{[^}]*display:\\s*none\\s*!important");
    }
}
