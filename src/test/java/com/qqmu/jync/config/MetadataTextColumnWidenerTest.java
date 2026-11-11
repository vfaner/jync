package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The metadata schema must be able to hold what the application stores in a {@code @Lob}
 * column.
 *
 * <p>Hibernate 7 resolves {@code @Lob} + {@code @Column} without an explicit length through the
 * dialect's capacity-dependent DDL type, and MySQL's smallest text capacity is
 * {@code TINYTEXT} (255 characters). A fresh MySQL metadata database therefore creates
 * {@code project.sync_config}, {@code etl_task.source_sql} and every other {@code @Lob}
 * column as {@code TINYTEXT}; the first real value — a project's sync config, an ETL query, a
 * schema snapshot — then fails with "Data too long for column" (error 1406). This class widens
 * those columns on the databases that have the problem; PostgreSQL ({@code text}), Oracle
 * ({@code CLOB}) and H2 ({@code CLOB}) never do.
 */
class MetadataTextColumnWidenerTest {

    private static MetadataTextColumnWidener.Column column(String table, String name,
                                                           String dataType, boolean nullable) {
        return new MetadataTextColumnWidener.Column(table, name, dataType, nullable);
    }

    @Test
    void mysqlTinyTextColumnBecomesLongText() {
        assertThat(MetadataTextColumnWidener.widenStatements("MySQL",
                List.of(column("project", "sync_config", "tinytext", true))))
                .containsExactly("ALTER TABLE `project` MODIFY COLUMN `sync_config` LONGTEXT NULL");
    }

    @Test
    void theNotNullConstraintSurvivesTheWidening() {
        assertThat(MetadataTextColumnWidener.widenStatements("MySQL",
                List.of(column("etl_task", "source_sql", "tinytext", false))))
                .containsExactly("ALTER TABLE `etl_task` MODIFY COLUMN `source_sql` LONGTEXT NOT NULL");
    }

    @Test
    void mariaDbHasTheSameCapacityProblem() {
        assertThat(MetadataTextColumnWidener.widenStatements("MariaDB",
                List.of(column("project", "sync_config", "tinytext", true))))
                .hasSize(1);
    }

    /**
     * {@code TEXT} and {@code MEDIUMTEXT} are never produced by the bug: a column declared with
     * an explicit length gets one of those, and the length is the bound its author chose.
     * Widening them would erase that bound, so the repair leaves them alone.
     */
    @Test
    void columnsWithADeliberateLengthAreLeftAlone() {
        assertThat(MetadataTextColumnWidener.widenStatements("MySQL", List.of(
                column("etl_task", "pre_sql", "text", true),
                column("etl_task", "source_filter", "text", true),
                column("change_log", "details", "mediumtext", true))))
                .isEmpty();
    }

    @Test
    void columnsThatAlreadyHoldEnoughAreLeftAlone() {
        assertThat(MetadataTextColumnWidener.widenStatements("MySQL", List.of(
                column("change_log", "details", "longtext", true),
                column("project", "name", "varchar", false),
                column("project", "enabled", "bit", false))))
                .isEmpty();
    }

    @Test
    void theDataTypeIsComparedCaseInsensitively() {
        assertThat(MetadataTextColumnWidener.widenStatements("MySQL",
                List.of(column("project", "sync_config", "TINYTEXT", true))))
                .hasSize(1);
    }

    @Test
    void otherMetadataDatabasesAreLeftUntouched() {
        List<MetadataTextColumnWidener.Column> narrow =
                List.of(column("project", "sync_config", "tinytext", true));

        assertThat(MetadataTextColumnWidener.widenStatements("PostgreSQL", narrow)).isEmpty();
        assertThat(MetadataTextColumnWidener.widenStatements("Oracle", narrow)).isEmpty();
        assertThat(MetadataTextColumnWidener.widenStatements("H2", narrow)).isEmpty();
        assertThat(MetadataTextColumnWidener.widenStatements(null, narrow)).isEmpty();
    }

    @Test
    void noColumnsMeansNoStatements() {
        assertThat(MetadataTextColumnWidener.widenStatements("MySQL", List.of())).isEmpty();
    }
}
