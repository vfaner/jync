package com.qqmu.jync.service.auth.oa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The OA clients and the config service normalise every user-typed field the same way:
 * a blank string must become absent, not an empty string, or "leave this field empty"
 * would be stored as a value that overrides a default. One implementation now backs all
 * of them, so the contract lives here instead of in four private copies.
 */
class OaTextTest {

    @Test
    void blankInputsBecomeAbsent() {
        assertThat(OaText.trimToNull(null)).isNull();
        assertThat(OaText.trimToNull("")).isNull();
        assertThat(OaText.trimToNull("   \t\n")).isNull();
    }

    @Test
    void surroundingWhitespaceIsStrippedFromRealValues() {
        assertThat(OaText.trimToNull("  admin  ")).isEqualTo("admin");
    }

    @Test
    void aBlankValueFallsBackToTheDefault() {
        assertThat(OaText.orDefault(null, "username")).isEqualTo("username");
        assertThat(OaText.orDefault("   ", "username")).isEqualTo("username");
        assertThat(OaText.orDefault("  login  ", "username")).isEqualTo("login");
    }
}
