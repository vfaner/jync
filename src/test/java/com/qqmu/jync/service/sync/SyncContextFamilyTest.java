package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * The context carries {@link DatabaseType#CUSTOM} for a custom connection, whose own family is
 * GENERIC. Every {@code type.getFamily()} check in the write pipeline therefore saw GENERIC and
 * skipped the real product's behavior. The context must expose the declared family and a
 * representative preset to the call sites.
 */
class SyncContextFamilyTest {

    private static DatabaseConfig config(DatabaseType type, DatabaseType.DialectFamily family) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(type);
        config.setDialectFamily(family);
        return config;
    }

    @Test
    void aCustomSourceResolvesToItsDeclaredFamily() {
        SyncContext ctx = SyncContext.builder()
                .sourceType(DatabaseType.CUSTOM)
                .sourceConfig(config(DatabaseType.CUSTOM, DatabaseType.DialectFamily.POSTGRES))
                .build();

        assertThat(ctx.resolveSourceFamily()).isEqualTo(DatabaseType.DialectFamily.POSTGRES);
        assertThat(ctx.effectiveSourceType()).isEqualTo(DatabaseType.POSTGRESQL);
    }

    @Test
    void aCustomTargetResolvesToItsDeclaredFamily() {
        SyncContext ctx = SyncContext.builder()
                .targetType(DatabaseType.CUSTOM)
                .targetConfig(config(DatabaseType.CUSTOM, DatabaseType.DialectFamily.ORACLE))
                .build();

        assertThat(ctx.resolveTargetFamily()).isEqualTo(DatabaseType.DialectFamily.ORACLE);
        assertThat(ctx.effectiveTargetType()).isEqualTo(DatabaseType.ORACLE);
    }

    @Test
    void aCustomConnectionWithoutAFamilyStaysCustomAndGeneric() {
        SyncContext ctx = SyncContext.builder()
                .sourceType(DatabaseType.CUSTOM)
                .sourceConfig(config(DatabaseType.CUSTOM, null))
                .build();

        assertThat(ctx.resolveSourceFamily()).isEqualTo(DatabaseType.DialectFamily.GENERIC);
        assertThat(ctx.effectiveSourceType()).isEqualTo(DatabaseType.CUSTOM);
    }

    @Test
    void aPresetTypeNeedsNoConfig() {
        // Hand-built contexts (tests, CDC replay) set only the type; they must keep working.
        SyncContext ctx = SyncContext.builder()
                .sourceType(DatabaseType.MYSQL)
                .targetType(DatabaseType.ORACLE)
                .build();

        assertThat(ctx.resolveSourceFamily()).isEqualTo(DatabaseType.DialectFamily.MYSQL);
        assertThat(ctx.resolveTargetFamily()).isEqualTo(DatabaseType.DialectFamily.ORACLE);
        assertThat(ctx.effectiveSourceType()).isEqualTo(DatabaseType.MYSQL);
        assertThat(ctx.effectiveTargetType()).isEqualTo(DatabaseType.ORACLE);
    }

    @Test
    void aCustomMysqlTargetOnlyClassifiesUpsertsWhenItsOwnUrlAsksForIt() {
        DatabaseConfig noFlag = config(DatabaseType.CUSTOM, DatabaseType.DialectFamily.MYSQL);
        noFlag.setCustomUrl("jdbc:mysql://db.example.com:3306/app");
        assertThat(SyncContext.builder().targetType(DatabaseType.CUSTOM)
                .targetConfig(noFlag).build().upsertCountsDistinguishInsertFromUpdate())
                .isFalse();

        DatabaseConfig withFlag = config(DatabaseType.CUSTOM, DatabaseType.DialectFamily.MYSQL);
        withFlag.setCustomUrl("jdbc:mysql://db.example.com:3306/app?useAffectedRows=true");
        assertThat(SyncContext.builder().targetType(DatabaseType.CUSTOM)
                .targetConfig(withFlag).build().upsertCountsDistinguishInsertFromUpdate())
                .isTrue();
    }

    @Test
    void aHandBuiltCustomContextCanCarryTheFamilyDirectly() {
        // The family fields let callers outside SyncContextFactory state the family without
        // materializing a whole DatabaseConfig.
        SyncContext ctx = SyncContext.builder()
                .sourceType(DatabaseType.CUSTOM)
                .sourceFamily(DatabaseType.DialectFamily.DB2)
                .build();

        assertThat(ctx.resolveSourceFamily()).isEqualTo(DatabaseType.DialectFamily.DB2);
        assertThat(ctx.effectiveSourceType()).isEqualTo(DatabaseType.DB2);
    }
}
