package com.qqmu.jync.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * A file input carries the page's {@code .input} class, but the browser paints the picker
 * button inside it natively: grey, square and out of place next to every other control.
 *
 * <p>{@code ::file-selector-button} is the part that has to be styled; styling the wrapper
 * alone (padding, border) changes nothing about the button the user actually clicks.
 */
class FileInputStyleContractTest {

    private final String css;

    FileInputStyleContractTest() throws IOException {
        css = new String(new ClassPathResource("static/css/app.css")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void theNativePickerButtonUsesThePagePalette() {
        assertThat(css).containsPattern(
                "\\.input\\[type=\"file\"\\]::file-selector-button\\s*\\{[^}]*"
                        + "background:\\s*var\\(--bg-soft\\)");
        assertThat(css).containsPattern(
                "\\.input\\[type=\"file\"\\]::file-selector-button\\s*\\{[^}]*"
                        + "border:\\s*1px solid var\\(--line\\)");
        assertThat(css).containsPattern(
                "\\.input\\[type=\"file\"\\]::file-selector-button\\s*\\{[^}]*"
                        + "border-radius:\\s*var\\(--radius-sm\\)");
    }

    @Test
    void thePickerIsClickableAcrossTheWholeField() {
        assertThat(css).containsPattern(
                "\\.input\\[type=\"file\"\\]\\s*\\{[^}]*cursor:\\s*pointer");
    }
}
