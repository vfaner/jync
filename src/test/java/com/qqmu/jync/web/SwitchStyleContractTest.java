package com.qqmu.jync.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The custom switch is a {@code label} holding a hidden checkbox plus a track span. Two
 * properties of the stylesheet are load-bearing:
 *
 * <ol>
 *   <li>{@code .field label} sets {@code display: block} at a higher specificity than
 *       {@code .switch}; a switch placed in a field therefore collapsed to a zero-width inline
 *       span — the track was invisible and had no click target, so the control looked dead.</li>
 *   <li>The states must be unmistakable at a glance: green when on, red when off.</li>
 * </ol>
 */
class SwitchStyleContractTest {

    private final String css;

    SwitchStyleContractTest() throws IOException {
        css = new String(new ClassPathResource("static/css/app.css")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void aSwitchInsideAFieldKeepsItsFlexRowInsteadOfCollapsing() {
        assertThat(css).containsPattern(
                "\\.field\\s+label\\.switch\\s*\\{[^}]*display:\\s*flex");
    }

    @Test
    void theSwitchIsGreenWhenOnAndRedWhenOff() {
        // Spring/Thymeleaf writes a hidden "_field" marker input right after a checkbox
        // th:field, so the track is NOT the adjacent sibling. With "+" the :checked rule
        // never matched: every switch stayed red and a click looked like it did nothing.
        assertThat(css).containsPattern(
                "\\.switch\\s+input:checked\\s*~\\s*\\.track\\s*\\{[^}]*background:\\s*var\\(--ok\\)");
        assertThat(css).containsPattern(
                "\\.switch\\s+\\.track\\s*\\{[^}]*background:\\s*var\\(--danger\\)");
    }
}
