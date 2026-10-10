package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * Routing decides whether a table's initial load may be chunked at all, so every PK shape
 * gets its own case: only "no key" may fall back to the classic streamed read.
 */
class FullLoadRouteTest {

    @Test
    void aSingleIntegralPkTakesTheNumericKeysetRoute() {
        TableMeta table = table(List.of("ID"), col("ID", Types.BIGINT), col("NAME", Types.VARCHAR));

        assertThat(FullLoadRoute.route(table)).isEqualTo(FullLoadRoute.KEYSET_NUMERIC);
    }

    @Test
    void aDecimalPkCountsAsNumericToo() {
        TableMeta table = table(List.of("AMOUNT"), col("AMOUNT", Types.DECIMAL));

        assertThat(FullLoadRoute.route(table)).isEqualTo(FullLoadRoute.KEYSET_NUMERIC);
    }

    @Test
    void aCompositePkTakesTheCompositeKeysetRoute() {
        TableMeta table = table(List.of("TENANT", "ID"),
                col("TENANT", Types.VARCHAR), col("ID", Types.BIGINT));

        assertThat(FullLoadRoute.route(table)).isEqualTo(FullLoadRoute.KEYSET_COMPOSITE);
    }

    @Test
    void aSingleVarcharPkTakesTheCompositeKeysetRoute() {
        TableMeta table = table(List.of("CODE"), col("CODE", Types.VARCHAR));

        assertThat(FullLoadRoute.route(table)).isEqualTo(FullLoadRoute.KEYSET_COMPOSITE);
    }

    @Test
    void aKeylessTableFallsBackToTheClassicStreamedLoad() {
        TableMeta table = table(List.of(), col("LINE", Types.VARCHAR));

        assertThat(FullLoadRoute.route(table)).isEqualTo(FullLoadRoute.STREAMING_FALLBACK);
    }

    @Test
    void pkNameCaseDifferencesAgainstColumnMetadataAreTolerated() {
        // Oracle reports metadata in upper case while a stored key list may not be.
        TableMeta table = table(List.of("id"), col("ID", Types.BIGINT));

        assertThat(FullLoadRoute.route(table)).isEqualTo(FullLoadRoute.KEYSET_NUMERIC);
    }

    @Test
    void aPkColumnMissingFromTheMetadataDegradesToCompositeNotFallback() {
        // Still keyed, so still chunkable — just without the "cheap integral boundary" label.
        TableMeta table = table(List.of("ID"));

        assertThat(FullLoadRoute.route(table)).isEqualTo(FullLoadRoute.KEYSET_COMPOSITE);
    }

    private static TableMeta table(List<String> pk, ColumnMeta... columns) {
        TableMeta table = new TableMeta();
        table.setName("T");
        table.setPrimaryKeys(new ArrayList<>(pk));
        table.setColumns(new ArrayList<>(List.of(columns)));
        return table;
    }

    private static ColumnMeta col(String name, int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        return column;
    }
}
