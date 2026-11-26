package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

class DataSyncServiceAdaptiveChunkTest {

    private static ColumnMeta column(String name, int type, Integer size) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(type);
        column.setSize(size);
        return column;
    }

    private static TableMeta table(ColumnMeta... columns) {
        TableMeta table = new TableMeta();
        table.setName("T");
        table.setColumns(java.util.List.of(columns));
        return table;
    }

    @Test
    void narrowTablesKeepTheConfiguredChunkSize() {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(100_000);

        int chunk = new DataSyncService(properties).chunkSizeFor(
                table(column("ID", Types.BIGINT, null),
                        column("NAME", Types.VARCHAR, 64)));

        assertThat(chunk).isEqualTo(100_000);
    }

    @Test
    void aLobColumnShrinksTheChunkButNotBelowTheFloor() {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(100_000);

        int chunk = new DataSyncService(properties).chunkSizeFor(
                table(column("ID", Types.BIGINT, null),
                        column("BODY", Types.CLOB, null)));

        assertThat(chunk).isLessThan(100_000).isGreaterThanOrEqualTo(1_000);
    }

    @Test
    void theAdaptiveModeCanBeDisabled() {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(100_000);
        properties.setAdaptiveChunkSize(false);

        assertThat(new DataSyncService(properties).chunkSizeFor(
                table(column("BODY", Types.CLOB, null)))).isEqualTo(100_000);
    }

    @Test
    void onlyConnectionShapedFailuresAreRetryable() {
        assertThat(DataSyncService.isRetryableCopyFailure(
                new SQLException("broken", "08006"))).isTrue();
        assertThat(DataSyncService.isRetryableCopyFailure(
                new SQLException("Connection is closed"))).isTrue();
        assertThat(DataSyncService.isRetryableCopyFailure(
                new SQLException("Error retrieving record: Can not read response from server."
                        + " Expected to read 273 bytes, read 237 bytes before connection was"
                        + " unexpectedly lost", null,
                        new java.io.EOFException("server closed the stream")))).isTrue();
        assertThat(DataSyncService.isRetryableCopyFailure(
                new NullPointerException(
                        "Cannot invoke \"com.mysql.cj.NativeSession.getCancelTimer()\""
                                + " because \"this.session\" is null"))).isTrue();
        assertThat(DataSyncService.isRetryableCopyFailure(
                new SQLException("deadlock", "40001"))).isTrue();
        assertThat(DataSyncService.isRetryableCopyFailure(
                new SQLException("lock wait timeout", "HY000", 1205))).isTrue();
        assertThat(DataSyncService.isRetryableCopyFailure(
                new SQLException("syntax error"))).isFalse();
        assertThat(DataSyncService.isRetryableCopyFailure(
                new StopRequestedException("stop"))).isFalse();
    }
}
