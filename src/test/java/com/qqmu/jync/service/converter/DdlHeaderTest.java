package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the object reference out of a CREATE header.
 *
 * <p>Both the override editor and the routine apply path need to know which schema an override
 * names: a saved statement that says {@code `test-rgh`.`InsertTestData`} creates the routine in
 * that schema on whichever server it runs against, which is exactly how a routine ends up
 * "already existing" somewhere the user is not looking.
 */
class DdlHeaderTest {

    @Test
    @DisplayName("the object type is read under the prefixes dialects allow")
    void readsTheTypeUnderDialectPrefixes() {
        assertThat(DdlHeader.objectType(
                "CREATE DEFINER=`root`@`%` PROCEDURE `p`() BEGIN END"))
                .isEqualTo("PROCEDURE");
        assertThat(DdlHeader.objectType("create or replace force view v as select 1"))
                .isEqualTo("VIEW");
    }

    @Test
    @DisplayName("MySQL-style qualified names split into schema and object")
    void readsABacktickQualifiedName() {
        String sql = "CREATE PROCEDURE `test-rgh`.`InsertTestData`() BEGIN END";

        assertThat(DdlHeader.schema(sql)).isEqualTo("test-rgh");
        assertThat(DdlHeader.name(sql)).isEqualTo("InsertTestData");
    }

    @Test
    @DisplayName("ANSI and SQL Server quoting split the same way")
    void readsAnsiAndBracketQuoting() {
        assertThat(DdlHeader.schema("CREATE VIEW \"APP\".\"V\" AS SELECT 1")).isEqualTo("APP");
        assertThat(DdlHeader.name("CREATE VIEW [dbo].[V] AS SELECT 1")).isEqualTo("V");
        assertThat(DdlHeader.schema("CREATE VIEW [dbo].[V] AS SELECT 1")).isEqualTo("dbo");
    }

    @Test
    @DisplayName("an unqualified name has no schema")
    void anUnqualifiedNameHasNoSchema() {
        assertThat(DdlHeader.schema("CREATE VIEW v AS SELECT 1")).isNull();
        assertThat(DdlHeader.name("CREATE VIEW v AS SELECT 1")).isEqualTo("v");
        assertThat(DdlHeader.name("CREATE PROCEDURE p() BEGIN END")).isEqualTo("p");
    }

    @Test
    @DisplayName("a bare body has no header to read")
    void aBodyOnlyStatementHasNoHeader() {
        assertThat(DdlHeader.objectType("BEGIN SELECT 1; END")).isNull();
        assertThat(DdlHeader.schema("BEGIN SELECT 1; END")).isNull();
        assertThat(DdlHeader.name(null)).isNull();
    }
}
