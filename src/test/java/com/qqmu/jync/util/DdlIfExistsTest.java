package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** DROP TABLE / DROP VIEW are turned into their idempotent end-state form. */
class DdlIfExistsTest {

    @Test
    void addsIfExistsToTableAndViewDrops() {
        assertThat(DdlIfExists.add("DROP TABLE t")).isEqualTo("DROP TABLE IF EXISTS t");
        assertThat(DdlIfExists.add("drop view v")).isEqualTo("drop view IF EXISTS v");
        assertThat(DdlIfExists.add("DROP TABLE IF EXISTS t")).isEqualTo("DROP TABLE IF EXISTS t");
    }

    @Test
    void keepsCommentsAndUsePrefixes() {
        assertThat(DdlIfExists.add("/* skipped */ DROP TABLE t"))
                .isEqualTo("/* skipped */ DROP TABLE IF EXISTS t");
        assertThat(DdlIfExists.add("USE app; DROP TABLE t"))
                .isEqualTo("USE app; DROP TABLE IF EXISTS t");
    }

    @Test
    void leavesOtherStatementsAlone() {
        for (String sql : new String[]{
                "ALTER TABLE t DROP COLUMN c",
                "DROP INDEX idx ON t",
                "DROP DATABASE d",
                "CREATE TABLE t (id INT)",
                "TRUNCATE TABLE t"}) {
            assertThat(DdlIfExists.add(sql)).isEqualTo(sql);
            assertThat(DdlIfExists.isDropTableOrView(sql)).isFalse();
        }
    }

    @Test
    void removeStripsOnlyTheHeadClause() {
        assertThat(DdlIfExists.remove("DROP TABLE IF EXISTS t")).isEqualTo("DROP TABLE t");
        assertThat(DdlIfExists.remove("DROP VIEW IF EXISTS v")).isEqualTo("DROP VIEW v");
        assertThat(DdlIfExists.remove("DROP TABLE t")).isEqualTo("DROP TABLE t");
    }

    @Test
    void aTableNamedIfExistsIsNotMistakenForTheClause() {
        // Identifier-quoted: the clause scan reads whole words only.
        assertThat(DdlIfExists.add("DROP TABLE \"if\"")).isEqualTo("DROP TABLE IF EXISTS \"if\"");
        assertThat(DdlIfExists.add("DROP TABLE if_exists")).isEqualTo("DROP TABLE IF EXISTS if_exists");
    }
}
