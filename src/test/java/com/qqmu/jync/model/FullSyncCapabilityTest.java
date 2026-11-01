package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The full-sync engine only knows how to read and write products that belong to one of the
 * dialect families. A product outside every family (H2, or a CUSTOM connection that never
 * declared one) must not be offered as a source or target of a real-time project — its
 * structure, type mapping and upsert contract are guesses the engine cannot make.
 */
class FullSyncCapabilityTest {

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {
            "MYSQL", "MARIADB", "ORACLE", "SQLSERVER", "DB2", "POSTGRESQL", "OPENGAUSS",
            "GAUSSDB", "DM", "KINGBASE", "OCEANBASE", "TIDB", "GOLDENDB", "TDSQL",
            "POLARDB", "GREATDB", "UPDRDB", "KUNDB", "RASESQL", "SEABOXSQL", "HIGHGO",
            "VASTBASE", "YASHANDB", "GBASE", "OSCAR", "HANA"
    })
    void everyFamilyPresetSupportsFullSync(DatabaseType type) {
        assertThat(type.supportsFullSync()).isTrue();
    }

    @Test
    void genericProductsDoNotSupportFullSync() {
        assertThat(DatabaseType.H2.supportsFullSync()).isFalse();
        assertThat(DatabaseType.CUSTOM.supportsFullSync()).isFalse();
        // Analytic / distributed / document engines: no family describes their SQL, so ETL only.
        for (DatabaseType engine : new DatabaseType[] {
                DatabaseType.DORIS, DatabaseType.CLICKHOUSE,
                DatabaseType.HIVE, DatabaseType.IMPALA, DatabaseType.ELASTICSEARCH,
                DatabaseType.MONGODB, DatabaseType.MONGODB_NATIVE}) {
            assertThat(engine.supportsFullSync()).as(engine.name()).isFalse();
        }
    }

    @Test
    void aCustomConnectionSupportsFullSyncOnlyWithADeclaredFamily() {
        DatabaseConfig declared = new DatabaseConfig();
        declared.setType(DatabaseType.CUSTOM);
        declared.setDialectFamily(DatabaseType.DialectFamily.ORACLE);
        assertThat(declared.supportsFullSync()).isTrue();

        DatabaseConfig undeclared = new DatabaseConfig();
        undeclared.setType(DatabaseType.CUSTOM);
        assertThat(undeclared.supportsFullSync()).isFalse();

        DatabaseConfig generic = new DatabaseConfig();
        generic.setType(DatabaseType.CUSTOM);
        generic.setDialectFamily(DatabaseType.DialectFamily.GENERIC);
        assertThat(generic.supportsFullSync()).isFalse();
    }

    @Test
    void aPresetConnectionInheritsItsOwnCapability() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.MYSQL);
        assertThat(config.supportsFullSync()).isTrue();

        config.setType(DatabaseType.H2);
        assertThat(config.supportsFullSync()).isFalse();
    }
}
