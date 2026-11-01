package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * Oracle-family dictionaries key unquoted identifiers upper case, so a lower-case configured
 * schema must be folded before it is used. A CUSTOM connection that declared Oracle speaks the
 * same dictionary.
 */
class SyncContextFactorySchemaFoldingTest {

    // Only the schema folding is under test; every collaborator stays null.
    private final SyncContextFactory factory =
            new SyncContextFactory(null, null, null, null, null, new SyncProperties(), null,
                    new PoolActivity(new SyncProperties()));

    private static DatabaseConfig db(DatabaseType type, DatabaseType.DialectFamily family) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(type);
        config.setDialectFamily(family);
        return config;
    }

    @Test
    void anOraclePresetFoldsItsSchema() {
        assertThat(factory.normalizeConfiguredSchema(db(DatabaseType.ORACLE, null), "app"))
                .isEqualTo("APP");
    }

    @Test
    void aCustomOracleConnectionFoldsItsSchemaTheSameWay() {
        assertThat(factory.normalizeConfiguredSchema(
                        db(DatabaseType.CUSTOM, DatabaseType.DialectFamily.ORACLE), "app"))
                .isEqualTo("APP");
    }

    /**
     * DB2 keys unquoted identifiers upper case exactly like Oracle, and the DB2 reader already
     * folds the schema for its catalog lookups. The target DDL quotes the schema verbatim, so a
     * lower-case configured name would address a schema DB2 does not have — the bulk plan path
     * already folds DB2, and this path must agree with it.
     */
    @Test
    void aDb2PresetFoldsItsSchema() {
        assertThat(factory.normalizeConfiguredSchema(db(DatabaseType.DB2, null), "app"))
                .isEqualTo("APP");
    }

    @Test
    void aCustomDb2ConnectionFoldsItsSchemaTheSameWay() {
        assertThat(factory.normalizeConfiguredSchema(
                        db(DatabaseType.CUSTOM, DatabaseType.DialectFamily.DB2), "app"))
                .isEqualTo("APP");
    }

    @Test
    void otherFamiliesKeepTheConfiguredCase() {
        assertThat(factory.normalizeConfiguredSchema(
                        db(DatabaseType.CUSTOM, DatabaseType.DialectFamily.POSTGRES), "mySchema"))
                .isEqualTo("mySchema");
        assertThat(factory.normalizeConfiguredSchema(db(DatabaseType.MYSQL, null), "mySchema"))
                .isEqualTo("mySchema");
    }
}
