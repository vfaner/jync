package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The analytic / distributed engines are ETL connections: the customer supplies the extraction
 * SQL and Jync only copies rows, so none of them needs a dialect family. Marking one MYSQL
 * (Doris) or POSTGRES (ClickHouse) would make the full-sync engine emit DDL their products
 * reject — {@code ENGINE=InnoDB} in Doris's case — so the family is deliberately GENERIC and
 * {@link DatabaseType#supportsFullSync()} stays false.
 */
class DatabaseTypePresetTest {

    private static String url(DatabaseType type) {
        return String.format(type.getUrlTemplate(), "localhost", type.getDefaultPort(), "db");
    }

    @Test
    void sapHanaConnectsThroughItsOwnDriverAndDatabaseNameParameter() {
        DatabaseType type = DatabaseType.HANA;

        assertThat(type.getDriverClassName()).isEqualTo("com.sap.db.jdbc.Driver");
        assertThat(type.getDefaultPort()).isEqualTo(30015);
        assertThat(url(type)).isEqualTo("jdbc:sap://localhost:30015/?databaseName=db");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsFullSync()).isFalse();
    }

    @Test
    void apacheDorisReusesTheBundledMysqlDriverOnTheFrontendQueryPort() {
        DatabaseType type = DatabaseType.DORIS;

        assertThat(type.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(type.getDefaultPort()).isEqualTo(9030);
        assertThat(url(type)).startsWith("jdbc:mysql://localhost:9030/db");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsFullSync()).isFalse();
    }

    @Test
    void clickHouseUsesItsOfficialDriverOnTheHttpPort() {
        DatabaseType type = DatabaseType.CLICKHOUSE;

        assertThat(type.getDriverClassName()).isEqualTo("com.clickhouse.jdbc.ClickHouseDriver");
        assertThat(type.getDefaultPort()).isEqualTo(8123);
        assertThat(url(type)).isEqualTo("jdbc:clickhouse://localhost:8123/db");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsFullSync()).isFalse();
    }

    @Test
    void hiveUsesHiveServer2() {
        DatabaseType type = DatabaseType.HIVE;

        assertThat(type.getDriverClassName()).isEqualTo("org.apache.hive.jdbc.HiveDriver");
        assertThat(type.getDefaultPort()).isEqualTo(10000);
        assertThat(url(type)).isEqualTo("jdbc:hive2://localhost:10000/db");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsFullSync()).isFalse();
    }

    @Test
    void impalaUsesTheClouderaDriverOnTheImpalaDaemonPort() {
        DatabaseType type = DatabaseType.IMPALA;

        assertThat(type.getDriverClassName()).isEqualTo("com.cloudera.impala.jdbc.Driver");
        assertThat(type.getDefaultPort()).isEqualTo(21050);
        assertThat(url(type)).isEqualTo("jdbc:impala://localhost:21050/db");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsFullSync()).isFalse();
    }

    @Test
    void elasticsearchUsesTheXpackSqlDriverAndIgnoresTheDatabaseName() {
        DatabaseType type = DatabaseType.ELASTICSEARCH;

        assertThat(type.getDriverClassName()).isEqualTo("org.elasticsearch.xpack.sql.jdbc.EsDriver");
        assertThat(type.getDefaultPort()).isEqualTo(9200);
        // The SQL endpoint has no database: the third format argument is simply not in the
        // template, and String.format ignores extras.
        assertThat(url(type)).isEqualTo("jdbc:es://http://localhost:9200");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsFullSync()).isFalse();
    }

    @Test
    void eachNewPresetIsSelectableByItsName() {
        for (DatabaseType type : new DatabaseType[] {
                DatabaseType.HANA, DatabaseType.DORIS, DatabaseType.CLICKHOUSE,
                DatabaseType.HIVE, DatabaseType.IMPALA, DatabaseType.ELASTICSEARCH,
                DatabaseType.MONGODB}) {
            assertThat(DatabaseType.fromName(type.name())).isSameAs(type);
        }
    }

    /**
     * MongoDB has no relational JDBC driver of its own; the only JDBC access is MongoDB's
     * Atlas SQL driver, whose connection string is just the host — a database path is not
     * accepted there, the database travels as a JDBC property instead. Atlas hosts are
     * usually addressed without a port, so a blank port must stay blank.
     */
    @Test
    void mongodbUsesTheAtlasSqlDriverAndAllowsAPortlessHost() {
        DatabaseType type = DatabaseType.MONGODB;

        assertThat(type.getDriverClassName()).isEqualTo("com.mongodb.jdbc.MongoDriver");
        assertThat(type.getDefaultPort()).isEqualTo(27017);
        assertThat(type.allowsBlankPort()).isTrue();
        assertThat(String.format(type.getPortlessUrlTemplate(), "atlas.example.net", "db"))
                .isEqualTo("jdbc:mongodb://atlas.example.net");
        assertThat(url(type)).isEqualTo("jdbc:mongodb://localhost:27017");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsFullSync()).isFalse();
    }
}
