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
 *
 * <p>The six analytic / search engines are the one deliberate exception, and only as a
 * source: they have no log-based CDC, so polling and full compare are all they can offer, and
 * the write path (DDL, keyed upsert, delete) has no meaning on them.
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

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {
            "DORIS", "CLICKHOUSE", "HIVE", "IMPALA", "ELASTICSEARCH", "MONGODB"
    })
    void pollingOnlyEnginesAreSourcesButNeverTargets(DatabaseType type) {
        assertThat(type.supportsSyncSource()).isTrue();
        assertThat(type.supportsSyncTarget()).isFalse();
        assertThat(type.pollingOnlySource()).isTrue();
        assertThat(type.supportsFullSync()).isTrue();
    }

    @Test
    void h2UndeclaredCustomAndNativeMongoStayOutsideRealTimeProjects() {
        for (DatabaseType type : new DatabaseType[] {
                DatabaseType.H2, DatabaseType.CUSTOM, DatabaseType.MONGODB_NATIVE}) {
            assertThat(type.supportsSyncSource()).as(type.name()).isFalse();
            assertThat(type.supportsSyncTarget()).as(type.name()).isFalse();
            assertThat(type.pollingOnlySource()).as(type.name()).isFalse();
            assertThat(type.supportsFullSync()).as(type.name()).isFalse();
        }
    }

    @Test
    void aCustomConnectionSupportsFullSyncOnlyWithADeclaredFamily() {
        DatabaseConfig declared = new DatabaseConfig();
        declared.setType(DatabaseType.CUSTOM);
        declared.setDialectFamily(DatabaseType.DialectFamily.ORACLE);
        assertThat(declared.supportsSyncSource()).isTrue();
        assertThat(declared.supportsSyncTarget()).isTrue();
        assertThat(declared.supportsFullSync()).isTrue();

        DatabaseConfig undeclared = new DatabaseConfig();
        undeclared.setType(DatabaseType.CUSTOM);
        assertThat(undeclared.supportsSyncSource()).isFalse();
        assertThat(undeclared.supportsSyncTarget()).isFalse();
        assertThat(undeclared.supportsFullSync()).isFalse();

        DatabaseConfig generic = new DatabaseConfig();
        generic.setType(DatabaseType.CUSTOM);
        generic.setDialectFamily(DatabaseType.DialectFamily.GENERIC);
        assertThat(generic.supportsSyncSource()).isFalse();
        assertThat(generic.supportsSyncTarget()).isFalse();
        assertThat(generic.supportsFullSync()).isFalse();
    }

    @Test
    void aPresetConnectionInheritsItsOwnCapability() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.MYSQL);
        assertThat(config.supportsSyncSource()).isTrue();
        assertThat(config.supportsSyncTarget()).isTrue();
        assertThat(config.supportsFullSync()).isTrue();

        config.setType(DatabaseType.CLICKHOUSE);
        assertThat(config.supportsSyncSource()).isTrue();
        assertThat(config.supportsSyncTarget()).isFalse();
        assertThat(config.pollingOnlySource()).isTrue();

        config.setType(DatabaseType.H2);
        assertThat(config.supportsSyncSource()).isFalse();
        assertThat(config.supportsSyncTarget()).isFalse();
        assertThat(config.supportsFullSync()).isFalse();
    }
}
