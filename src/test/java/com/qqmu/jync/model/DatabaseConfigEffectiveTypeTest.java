package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A CUSTOM connection speaks whichever family the user declared. Every family decision in the
 * sync pipeline (type mapping, DDL, schema folding, identity reseed, …) must see that family,
 * not the GENERIC that {@link DatabaseType#CUSTOM} carries on its own.
 */
class DatabaseConfigEffectiveTypeTest {

    private static DatabaseConfig custom(DatabaseType.DialectFamily family) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.CUSTOM);
        config.setDialectFamily(family);
        return config;
    }

    @Test
    void aCustomConnectionResolvesToAPresetOfItsDeclaredFamily() {
        assertThat(custom(DatabaseType.DialectFamily.ORACLE).effectiveType())
                .isEqualTo(DatabaseType.ORACLE);
        assertThat(custom(DatabaseType.DialectFamily.POSTGRES).effectiveType())
                .isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(custom(DatabaseType.DialectFamily.MYSQL).effectiveType())
                .isEqualTo(DatabaseType.MYSQL);
        assertThat(custom(DatabaseType.DialectFamily.SQLSERVER).effectiveType())
                .isEqualTo(DatabaseType.SQLSERVER);
        assertThat(custom(DatabaseType.DialectFamily.DB2).effectiveType())
                .isEqualTo(DatabaseType.DB2);
    }

    @Test
    void aCustomConnectionWithoutAFamilyStaysCustom() {
        // GENERIC has no preset to stand in for it; the generic dialect must stay in charge.
        assertThat(custom(null).effectiveType()).isEqualTo(DatabaseType.CUSTOM);
        assertThat(custom(DatabaseType.DialectFamily.GENERIC).effectiveType())
                .isEqualTo(DatabaseType.CUSTOM);
    }

    @Test
    void aCustomMysqlUrlIsClassifiedByItsOwnAffectedRowsFlag() {
        // The preset's URL carries useAffectedRows=true, but a CUSTOM connection is whatever
        // URL the user pasted. Standing a representative preset in for it must not invent the
        // flag: without it MySQL answers 1 for both insert and update.
        DatabaseConfig withFlag = custom(DatabaseType.DialectFamily.MYSQL);
        withFlag.setCustomUrl("jdbc:mysql://db.example.com:3306/app?useAffectedRows=true");
        assertThat(withFlag.upsertCountsDistinguishInsertFromUpdate()).isTrue();

        DatabaseConfig withoutFlag = custom(DatabaseType.DialectFamily.MYSQL);
        withoutFlag.setCustomUrl("jdbc:mysql://db.example.com:3306/app");
        assertThat(withoutFlag.upsertCountsDistinguishInsertFromUpdate()).isFalse();
    }

    @Test
    void aPresetConnectionIsItsOwnEffectiveType() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.ORACLE);
        // A stale family left behind by a type change must not override the preset.
        config.setDialectFamily(DatabaseType.DialectFamily.MYSQL);

        assertThat(config.effectiveType()).isEqualTo(DatabaseType.ORACLE);
    }
}
