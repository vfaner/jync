package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The create/edit form is two side-by-side cards and the fan-out search is a compact field in
 * the card head. Both live only in CSS; deleting the rules silently stacked the cards and
 * stretched the search across the card, which is hard to notice in a backend test.
 */
class ProjectFormTwoCardLayoutTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    void theProjectFormIsATwoColumnGrid() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);

        assertThat(css).contains(".project-cards {");
        assertThat(css).contains("grid-template-columns: repeat(2, minmax(0, 1fr))");
        // .card + .card adds a vertical margin; inside the grid that would push the right
        // card down so the two modules no longer line up.
        assertThat(css).contains(".project-cards > .card + .card { margin-top: 0; }");
    }

    @Test
    void theTargetPickerMenuIsStyledAsADropdown() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);

        // The picker is a script-filled div, not a native <select>; without these rules the
        // candidates render in normal flow as a row of unstyled buttons.
        assertThat(css).contains(".target-picker { position: relative; }");
        assertThat(css).contains(".target-menu {");
        // The menu is positioned from the input's viewport rect by app.js; absolute
        // positioning would be clipped by the card and land at the wrong offset.
        assertThat(css).contains("position: fixed");
        assertThat(css).contains(".target-menu-item {");
        assertThat(css).contains(".target-menu-type {");
    }

    @Test
    void theFanoutSearchStaysCompactInTheCardHead() throws Exception {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);

        assertThat(css).contains(".fanout-card .card-head .fanout-search-input {");
        assertThat(css).contains("width: 200px");
        assertThat(css).contains(".fanout-table-wrap { max-height: 320px; overflow: auto; }");
    }
}
