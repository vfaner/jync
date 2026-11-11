package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The binlog's Query events carry more than table DDL: privileges, maintenance and account
 * management are written as statements too. Filing those as skipped-DDL tasks turns every
 * FLUSH PRIVILEGES into an "action required" row and forces column projection on.
 */
class CdcDdlStatementsTest {

    @Test
    void privilegedAndMaintenanceStatementsDoNotChangeTableStructure() {
        assertThat(CdcDdlStatements.changesTableStructure(
                "GRANT SELECT ON app.* TO 'reader'@'%'")).isFalse();
        assertThat(CdcDdlStatements.changesTableStructure(
                "REVOKE ALL PRIVILEGES ON *.* FROM 'reader'@'%'")).isFalse();
        assertThat(CdcDdlStatements.changesTableStructure("ANALYZE TABLE orders")).isFalse();
        assertThat(CdcDdlStatements.changesTableStructure("OPTIMIZE TABLE orders")).isFalse();
        assertThat(CdcDdlStatements.changesTableStructure("FLUSH PRIVILEGES")).isFalse();
        assertThat(CdcDdlStatements.changesTableStructure(
                "CREATE USER 'reader'@'%' IDENTIFIED BY 'secret'")).isFalse();
        assertThat(CdcDdlStatements.changesTableStructure("DROP USER 'reader'@'%'")).isFalse();
        assertThat(CdcDdlStatements.changesTableStructure("CREATE DATABASE reporting")).isFalse();
    }

    @Test
    void tableDdlStillChangesStructure() {
        assertThat(CdcDdlStatements.changesTableStructure(
                "ALTER TABLE orders ADD COLUMN note VARCHAR(64)")).isTrue();
        assertThat(CdcDdlStatements.changesTableStructure("CREATE TABLE t (id INT)")).isTrue();
        assertThat(CdcDdlStatements.changesTableStructure("DROP TABLE t")).isTrue();
        assertThat(CdcDdlStatements.changesTableStructure("RENAME TABLE a TO b")).isTrue();
        assertThat(CdcDdlStatements.changesTableStructure("TRUNCATE TABLE t")).isTrue();
        // Unknown statements keep the conservative default: a task, never a silent skip.
        assertThat(CdcDdlStatements.changesTableStructure("SOMETHING NEW")).isTrue();
    }

    /**
     * A dropped database is not a per-table task: the dialog would offer to run the
     * statement verbatim on the target connection, where it can delete a same-named
     * database. CREATE/ALTER DATABASE are already excluded; DROP must be too.
     */
    @Test
    void droppingADatabaseIsNotFiledAsAnExecutableTableTask() {
        assertThat(CdcDdlStatements.changesTableStructure("DROP DATABASE reporting")).isFalse();
        assertThat(CdcDdlStatements.changesTableStructure("drop schema reporting")).isFalse();
    }

    @Test
    void leadingWhitespaceAndCaseDoNotMatter() {
        assertThat(CdcDdlStatements.changesTableStructure("  grant select on app.* to x"))
                .isFalse();
    }

    /**
     * MySQL keeps versioned comments ({@code /*!NNNNN ...}, and MariaDB's {@code /*M!...})
     * in the binlog Query text. The content is SQL the server executed, not a note: dropping
     * it leaves an empty statement, which is filed as a structural change with no table and
     * slips past the schema and table filters.
     */
    @Test
    void versionedCommentsAreUnwrappedNotDiscarded() {
        assertThat(CdcDdlStatements.stripLeadingNoise(
                "/*!40101 ALTER TABLE `app`.`orders` ADD COLUMN `note` INT */").trim())
                .isEqualTo("ALTER TABLE `app`.`orders` ADD COLUMN `note` INT");
        assertThat(CdcDdlStatements.changesTableStructure("/*!40101 ANALYZE TABLE orders */"))
                .isFalse();
        assertThat(CdcDdlStatements.changesTableStructure(
                "/*M!100100 OPTIMIZE TABLE orders */")).isFalse();
        assertThat(CdcDdlStatements.changesTableStructure(
                "/*!40101 ALTER TABLE orders ADD COLUMN note INT */")).isTrue();
    }
}
