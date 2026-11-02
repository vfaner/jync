package com.qqmu.jync.service.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MigrationGlobTest {

    @Test
    void splitsOnCommasAndNewlinesAndDropsBlanks() {
        assertThat(MigrationGlob.split(" orders-* ,\n users\r\n\n , audit_?"))
                .containsExactly("orders-*", "users", "audit_?");
        assertThat(MigrationGlob.split(null)).isEmpty();
        assertThat(MigrationGlob.split("   ")).isEmpty();
    }

    @Test
    void aGlobMatchesTheWholeNameOnly() {
        assertThat(MigrationGlob.matches("orders-2026", "orders-*")).isTrue();
        assertThat(MigrationGlob.matches("2026-orders", "orders-*")).isFalse();
        assertThat(MigrationGlob.matches("orders-2026", "orders-2026")).isTrue();
        assertThat(MigrationGlob.matches("orders-20266", "orders-202?")).isFalse();
    }

    @Test
    void aQuestionMarkMatchesExactlyOneCharacter() {
        assertThat(MigrationGlob.matches("audit_1", "audit_?")).isTrue();
        assertThat(MigrationGlob.matches("audit_10", "audit_?")).isFalse();
    }

    @Test
    void aBlankPatternMatchesEverything() {
        assertThat(MigrationGlob.matchesAny("anything", null)).isTrue();
        assertThat(MigrationGlob.matchesAny("anything", "  ")).isTrue();
    }

    @Test
    void anyListedGlobMatches() {
        assertThat(MigrationGlob.matchesAny("users", "orders-*, users")).isTrue();
        assertThat(MigrationGlob.matchesAny("audit", "orders-*, users")).isFalse();
    }

    @Test
    void anExplicitPrefixIsDetectedOnlyWhenAPatternPartStartsWithIt() {
        assertThat(MigrationGlob.namesPrefix("system.users, orders", "system.")).isTrue();
        assertThat(MigrationGlob.namesPrefix(" orders, system.users", "system.")).isTrue();
        assertThat(MigrationGlob.namesPrefix("orders-*, users", "system.")).isFalse();
        assertThat(MigrationGlob.namesPrefix(null, "system.")).isFalse();
    }
}
