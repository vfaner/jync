package com.qqmu.jync.service.migration.kingbase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.migration.MigrationTargetNaming;

class KingbaseTableNameTest {

    @Test
    void theTargetNameFollowsTheChosenNaming() {
        assertThat(KingbaseTableName.resolve(MigrationTargetNaming.SAME, "", "", "orders"))
                .isEqualTo("orders");
        assertThat(KingbaseTableName.resolve(MigrationTargetNaming.PREFIX, "m_", "", "orders"))
                .isEqualTo("m_orders");
        assertThat(KingbaseTableName.resolve(MigrationTargetNaming.SUFFIX, "", "_old", "orders"))
                .isEqualTo("orders_old");
    }

    @Test
    void anEmptyTargetNameIsRejected() {
        assertThatThrownBy(() -> KingbaseTableName.resolve(
                MigrationTargetNaming.SAME, "", "", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.options.tableName.invalid");
    }

    @Test
    void aNulByteIsRejected() {
        assertThatThrownBy(() -> KingbaseTableName.resolve(
                MigrationTargetNaming.SUFFIX, "", "\u0000", "orders"))
                .hasMessage("error.migration.options.tableName.invalid");
    }

    @Test
    void aNameLongerThanSixtyThreeBytesIsRejected() {
        assertThatThrownBy(() -> KingbaseTableName.resolve(
                MigrationTargetNaming.SAME, "", "", "x".repeat(64)))
                .hasMessage("error.migration.options.tableName.invalid");

        assertThat(KingbaseTableName.resolve(
                MigrationTargetNaming.SAME, "", "", "x".repeat(63))).hasSize(63);
    }

    @Test
    void anIllegalSchemaIsRejected() {
        assertThatThrownBy(() -> KingbaseTableName.validateSchema("x".repeat(64)))
                .hasMessage("error.migration.options.schema.invalid");
        assertThatThrownBy(() -> KingbaseTableName.validateSchema(" "))
                .hasMessage("error.migration.options.schema.invalid");

        KingbaseTableName.validateSchema("public");
        KingbaseTableName.validateSchema("x".repeat(63));
    }

    @Test
    void aBlankSchemaFallsBackToTheConnectionsCurrentSchema() {
        assertThat(KingbaseTableName.resolveSchema("  ", "public")).isEqualTo("public");
        assertThat(KingbaseTableName.resolveSchema("sales", "public")).isEqualTo("sales");
    }

    @Test
    void aConfiguredSchemaDoesNotAskTheDriver() throws Exception {
        Connection connection = mock(Connection.class);

        assertThat(KingbaseTableName.resolveSchema(" sales ", connection)).isEqualTo("sales");

        verifyNoInteractions(connection);
    }

    @Test
    void aBlankSchemaAsksTheDriverForTheCurrentOne() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getSchema()).thenReturn("public");

        assertThat(KingbaseTableName.resolveSchema(null, connection)).isEqualTo("public");
    }

    @Test
    void aDriverWithoutGetSchemaFallsBackToNull() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getSchema()).thenThrow(new AbstractMethodError());

        assertThat(KingbaseTableName.resolveSchema(" ", connection)).isNull();
    }
}
