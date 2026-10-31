package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The native MongoDB preset is not a JDBC product: it exists so a self-hosted Community
 * {@code mongod} can be an ETL source through the bundled synchronous driver. It must stay
 * out of everything JDBC-shaped — no driver class, no target role, no full-sync engine.
 *
 * <p>It coexists with {@link DatabaseType#MONGODB}, which remains the Atlas SQL JDBC preset.
 */
class NativeMongoPresetTest {

    @Test
    void nativeMongoCarriesNoJdbcDriverAndIsSourceOnly() {
        DatabaseType type = DatabaseType.MONGODB_NATIVE;

        assertThat(type.getDriverClassName()).isNull();
        assertThat(type.hasJdbcDriver()).isFalse();
        assertThat(type.getDefaultPort()).isEqualTo(27017);
        assertThat(type.allowsBlankPort()).isFalse();
        assertThat(type.getFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(type.supportsFullSync()).isFalse();
        assertThat(type.supportsEtlTarget()).isFalse();
    }

    @Test
    void nativeMongoUriTemplateSpeaksTheMongoProtocol() {
        assertThat(String.format(DatabaseType.MONGODB_NATIVE.getUrlTemplate(),
                "localhost", 27017, "shop"))
                .isEqualTo("mongodb://localhost:27017/shop");
    }

    @Test
    void nativeMongoIsSelectableByName() {
        assertThat(DatabaseType.fromName("MONGODB_NATIVE")).isSameAs(DatabaseType.MONGODB_NATIVE);
        assertThat(DatabaseType.fromName(" mongodb_native ")).isSameAs(DatabaseType.MONGODB_NATIVE);
    }

    @Test
    void atlasSqlAndNativeMongoCoexist() {
        assertThat(DatabaseType.MONGODB).isNotSameAs(DatabaseType.MONGODB_NATIVE);
        assertThat(DatabaseType.MONGODB.hasJdbcDriver()).isTrue();
    }
}
