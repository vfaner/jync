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
    @DisplayName("a doubled delimiter is one character of the name, not the end of it")
    void unescapesDoubledDelimiters() {
        // The override's pre-drop is built from this name; truncating "V]]X" to "V" made the
        // cleanup target a different object than the statement it precedes.
        assertThat(DdlHeader.name("CREATE VIEW [dbo].[V]]X] AS SELECT 1")).isEqualTo("V]X");
        assertThat(DdlHeader.name("CREATE VIEW \"s\".\"a\"\"b\" AS SELECT 1")).isEqualTo("a\"b");
        assertThat(DdlHeader.name("CREATE VIEW `s`.`a``b` AS SELECT 1")).isEqualTo("a`b");
    }

    @Test
    @DisplayName("a three-part name keeps its last two parts")
    void readsTheLastTwoPartsOfAThreePartName() {
        assertThat(DdlHeader.schema("CREATE VIEW MyDb.dbo.V AS SELECT 1")).isEqualTo("dbo");
        assertThat(DdlHeader.name("CREATE VIEW MyDb.dbo.V AS SELECT 1")).isEqualTo("V");
    }

    @Test
    @DisplayName("CREATE OR ALTER and a leading comment still read as a header")
    void readsCreateOrAlterAndLeadingComments() {
        assertThat(DdlHeader.objectType("CREATE OR ALTER VIEW dbo.V AS SELECT 1"))
                .isEqualTo("VIEW");
        assertThat(DdlHeader.name("CREATE OR ALTER VIEW dbo.V AS SELECT 1")).isEqualTo("V");
        assertThat(DdlHeader.name("/* generated */ CREATE VIEW dbo.V AS SELECT 1"))
                .isEqualTo("V");
        assertThat(DdlHeader.name("-- generated\nCREATE VIEW dbo.V AS SELECT 1")).isEqualTo("V");
    }

    @Test
    @DisplayName("only OR REPLACE / OR ALTER counts as replacing in place")
    void recognisesReplacingHeaders() {
        assertThat(DdlHeader.replacesInPlace("CREATE OR REPLACE VIEW v AS SELECT 1")).isTrue();
        assertThat(DdlHeader.replacesInPlace("CREATE OR ALTER VIEW v AS SELECT 1")).isTrue();
        assertThat(DdlHeader.replacesInPlace("CREATE VIEW v AS SELECT 1")).isFalse();
        assertThat(DdlHeader.replacesInPlace("/* x */ create or replace view v AS SELECT 1"))
                .isTrue();
    }

    @Test
    @DisplayName("a bare body has no header to read")
    void aBodyOnlyStatementHasNoHeader() {
        assertThat(DdlHeader.objectType("BEGIN SELECT 1; END")).isNull();
        assertThat(DdlHeader.schema("BEGIN SELECT 1; END")).isNull();
        assertThat(DdlHeader.name(null)).isNull();
    }
}
