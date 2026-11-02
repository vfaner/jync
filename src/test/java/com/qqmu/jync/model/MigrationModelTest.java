package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class MigrationModelTest {

    @Test
    void migrationTypeKeysRoundTripAndAreUnique() {
        assertThat(Arrays.stream(MigrationType.values()).map(MigrationType::key).distinct().count())
                .isEqualTo(MigrationType.values().length);
        for (MigrationType type : MigrationType.values()) {
            assertThat(MigrationType.fromKey(type.key())).isSameAs(type);
        }
    }

    @Test
    void unknownMigrationTypeKeyIsRejected() {
        assertThatThrownBy(() -> MigrationType.fromKey("oracle-to-dameng"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheImplementedTypeIsAvailable() {
        assertThat(MigrationType.ES_TO_EASYSEARCH.available()).isTrue();
        assertThat(MigrationType.MONGODB_TO_MONGODB.available()).isFalse();
        assertThat(MigrationType.MONGODB_TO_KINGBASE.available()).isFalse();
    }

    @Test
    void aNewConnectionStartsWithSafeDefaults() {
        MigrationConnection connection = new MigrationConnection();
        connection.onCreate();

        assertThat(connection.getAuthType()).isEqualTo(MigrationConnectionAuthType.NONE);
        assertThat(connection.isVerifyTls()).isTrue();
        assertThat(connection.getConnectTimeoutMs()).isEqualTo(5000);
        assertThat(connection.getRequestTimeoutMs()).isEqualTo(60000);
        assertThat(connection.getCreatedAt()).isNotNull();
        assertThat(connection.getUpdatedAt()).isNotNull();
    }

    @Test
    void aNewTaskIsDisabledAndTimestamped() {
        MigrationTask task = new MigrationTask();
        task.onCreate();

        assertThat(task.getEnabled()).isFalse();
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();
    }

    @Test
    void aNewRunStartsWithZeroCounters() {
        MigrationRun run = new MigrationRun();

        assertThat(run.getIndicesTotal()).isZero();
        assertThat(run.getIndicesSucceeded()).isZero();
        assertThat(run.getIndicesFailed()).isZero();
        assertThat(run.getDocumentsRead()).isZero();
        assertThat(run.getDocumentsWritten()).isZero();
    }

    @Test
    void auditActionsCoverDedicatedMigration() {
        assertThat(AuditAction.valueOf("MIGRATION_CONNECTION_SAVE")).isNotNull();
        assertThat(AuditAction.valueOf("MIGRATION_CONNECTION_DELETE")).isNotNull();
        assertThat(AuditAction.valueOf("MIGRATION_TASK_SAVE")).isNotNull();
        assertThat(AuditAction.valueOf("MIGRATION_TASK_DELETE")).isNotNull();
    }
}
