package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The analytic / distributed engines carry no dialect family: the customer supplies the
 * extraction SQL in ETL, and the full-sync write path (DDL, keyed upsert, delete) has no
 * meaning on them. Marking one MYSQL (Doris) or POSTGRES (ClickHouse) would make the engine
 * emit DDL their products reject — {@code ENGINE=InnoDB} in Doris's case.
 *
 * <p>They still take part in real-time sync as <em>sources</em>: a read-only dialect handles
 * quoting and pagination, and polling / full compare are the only strategies they offer.
 * {@link DatabaseType#supportsSyncSource()} is therefore true while
 * {@link DatabaseType#supportsSyncTarget()} stays false. SAP HANA is unrelated to this
 * exception: its ANSI-adjacent grammar needs a dedicated family, so it supports both roles.
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
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.HANA);
        assertThat(type.supportsFullSync()).isTrue();
    }

    @Test
    void apacheDorisReusesTheBundledMysqlDriverOnTheFrontendQueryPort() {
        DatabaseType type = DatabaseType.DORIS;

        assertThat(type.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(type.getDefaultPort()).isEqualTo(9030);
        assertThat(url(type)).startsWith("jdbc:mysql://localhost:9030/db");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsSyncSource()).isTrue();
        assertThat(type.supportsSyncTarget()).isFalse();
        assertThat(type.supportsFullSync()).isTrue();
    }

    @Test
    void clickHouseUsesItsOfficialDriverOnTheHttpPort() {
        DatabaseType type = DatabaseType.CLICKHOUSE;

        assertThat(type.getDriverClassName()).isEqualTo("com.clickhouse.jdbc.ClickHouseDriver");
        assertThat(type.getDefaultPort()).isEqualTo(8123);
        assertThat(url(type)).isEqualTo("jdbc:clickhouse://localhost:8123/db");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsSyncSource()).isTrue();
        assertThat(type.supportsSyncTarget()).isFalse();
        assertThat(type.supportsFullSync()).isTrue();
    }

    @Test
    void hiveUsesHiveServer2() {
        DatabaseType type = DatabaseType.HIVE;

        assertThat(type.getDriverClassName()).isEqualTo("org.apache.hive.jdbc.HiveDriver");
        assertThat(type.getDefaultPort()).isEqualTo(10000);
        assertThat(url(type)).isEqualTo("jdbc:hive2://localhost:10000/db");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsSyncSource()).isTrue();
        assertThat(type.supportsSyncTarget()).isFalse();
        assertThat(type.supportsFullSync()).isTrue();
    }

    @Test
    void impalaUsesTheClouderaDriverOnTheImpalaDaemonPort() {
        DatabaseType type = DatabaseType.IMPALA;

        assertThat(type.getDriverClassName()).isEqualTo("com.cloudera.impala.jdbc.Driver");
        assertThat(type.getDefaultPort()).isEqualTo(21050);
        assertThat(url(type)).isEqualTo("jdbc:impala://localhost:21050/db");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsSyncSource()).isTrue();
        assertThat(type.supportsSyncTarget()).isFalse();
        assertThat(type.supportsFullSync()).isTrue();
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
        assertThat(type.supportsSyncSource()).isTrue();
        assertThat(type.supportsSyncTarget()).isFalse();
        assertThat(type.supportsFullSync()).isTrue();
    }

    @Test
    void eachNewPresetIsSelectableByItsName() {
        for (DatabaseType type : new DatabaseType[] {
                DatabaseType.HANA, DatabaseType.DORIS, DatabaseType.CLICKHOUSE,
                DatabaseType.HIVE, DatabaseType.IMPALA, DatabaseType.ELASTICSEARCH,
                DatabaseType.MONGODB, DatabaseType.MONGODB_NATIVE}) {
            assertThat(DatabaseType.fromName(type.name())).isSameAs(type);
        }
    }

    /**
     * Elasticsearch's and MongoDB's SQL interfaces can only read. Every other preset — even
     * the ETL-only analytic engines — can at least receive writes, so only these two are
     * refused as an ETL target.
     */
    @Test
    void readOnlySqlProductsCannotBeEtlTargets() {
        assertThat(DatabaseType.ELASTICSEARCH.supportsEtlTarget()).isFalse();
        assertThat(DatabaseType.MONGODB.supportsEtlTarget()).isFalse();
        assertThat(DatabaseType.MONGODB_NATIVE.supportsEtlTarget()).isFalse();

        for (DatabaseType type : new DatabaseType[] {
                DatabaseType.MYSQL, DatabaseType.HANA, DatabaseType.DORIS,
                DatabaseType.CLICKHOUSE, DatabaseType.HIVE, DatabaseType.IMPALA,
                DatabaseType.CUSTOM}) {
            assertThat(type.supportsEtlTarget()).as(type.name()).isTrue();
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
        assertThat(type.supportsSyncSource()).isTrue();
        assertThat(type.supportsSyncTarget()).isFalse();
        assertThat(type.supportsFullSync()).isTrue();
    }

    @Test
    void tdengineUsesTheWebSocketDriverOnTheRestPort() {
        DatabaseType type = DatabaseType.TDENGINE;

        assertThat(type.getDriverClassName()).isEqualTo("com.taosdata.jdbc.ws.WebSocketDriver");
        assertThat(type.getDefaultPort()).isEqualTo(6041);
        assertThat(url(type)).isEqualTo("jdbc:TAOS-WS://localhost:6041/db");
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsSyncSource()).isTrue();
        assertThat(type.supportsSyncTarget()).isFalse();
        assertThat(type.pollingOnlySource()).isTrue();
        assertThat(type.supportsEtlTarget()).isTrue();
        assertThat(DatabaseCategory.of(type)).isEqualTo(DatabaseCategory.ANALYTICS);
    }

    @Test
    void tdengineIsSelectableByItsName() {
        assertThat(DatabaseType.fromName("TDENGINE")).isSameAs(DatabaseType.TDENGINE);
    }

    /**
     * A wrong jar is the most common reason an external-driver preset fails to connect:
     * TDengine's plain jar lacks Netty, Hive's plain jar lacks the Hadoop client, and the
     * MongoDB SQL driver's plain jar lacks its dependencies. The form's placeholder and hint
     * line therefore name the exact artifact the vendor publishes.
     */
    @Test
    void externalDriverPresetsNameTheirVendorJar() {
        assertThat(DatabaseType.GAUSSDB.driverJarHint()).isEqualTo("gaussdbjdbc.jar");
        assertThat(DatabaseType.GBASE.driverJarHint())
                .isEqualTo("gbase-connector-java-9.5.3.0-build1-bin.jar");
        assertThat(DatabaseType.OSCAR.driverJarHint()).isEqualTo("oscarJDBC.jar");
        assertThat(DatabaseType.HANA.driverJarHint()).isEqualTo("ngdbc-2.30.12.jar");
        assertThat(DatabaseType.CLICKHOUSE.driverJarHint())
                .isEqualTo("clickhouse-jdbc-0.11.0-all.jar");
        assertThat(DatabaseType.HIVE.driverJarHint())
                .isEqualTo("hive-jdbc-3.1.3-standalone.jar");
        assertThat(DatabaseType.IMPALA.driverJarHint()).isEqualTo("ImpalaJDBC42.jar");
        assertThat(DatabaseType.ELASTICSEARCH.driverJarHint())
                .isEqualTo("x-pack-sql-jdbc-7.17.22.jar");
        assertThat(DatabaseType.MONGODB.driverJarHint())
                .isEqualTo("mongodb-jdbc-4.0.3-all.jar");
        assertThat(DatabaseType.TDENGINE.driverJarHint())
                .isEqualTo("taos-jdbcdriver-3.10.0-dist.jar");
    }

    @Test
    void bundledPresetsAndCustomHaveNoJarToRecommend() {
        // The bundled-driver presets connect without a jar; the two vendor-driver presets
        // default to the bundled Connector/J, so their optional override keeps the generic
        // placeholder instead of naming a jar most deployments never fill in.
        for (DatabaseType type : new DatabaseType[] {
                DatabaseType.MYSQL, DatabaseType.DM, DatabaseType.POSTGRESQL,
                DatabaseType.DB2, DatabaseType.H2, DatabaseType.GOLDENDB,
                DatabaseType.GREATDB, DatabaseType.CUSTOM, DatabaseType.MONGODB_NATIVE}) {
            assertThat(type.driverJarHint()).as(type.name()).isNull();
        }
    }
}
