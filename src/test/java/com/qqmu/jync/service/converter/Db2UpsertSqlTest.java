package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * DB2 MERGE builds its row source as {@code USING (VALUES (?, ...))}. An untyped parameter
 * marker there is resolved once per place its source column is consumed (ON predicate,
 * WHEN MATCHED UPDATE, WHEN NOT MATCHED INSERT); when the resolutions disagree DB2 raises
 * SQL0418 / SQLSTATE 42610 ("<column> already resolved to different type"). The markers must
 * therefore carry an explicit CAST to the target column type.
 */
class Db2UpsertSqlTest {

    private final Db2Dialect db2 = new Db2Dialect();

    @Test
    void mergeValuesMarkersAreCastToTheDeclaredTypes() {
        String sql = db2.getUpsertSql("APP", "JYNC_SYNC_TEST",
                List.of("ID", "FCS", "NOTE"),
                List.of("ID"),
                Map.of("ID", Types.INTEGER, "FCS", Types.VARCHAR, "NOTE", Types.VARCHAR),
                false,
                Map.of("ID", "INTEGER", "FCS", "VARCHAR(36)", "NOTE", "VARCHAR(200)"));

        assertThat(sql).startsWith(
                "MERGE INTO \"APP\".\"JYNC_SYNC_TEST\" AS t USING (VALUES ("
                        + "CAST(? AS INTEGER), CAST(? AS VARCHAR(36)), CAST(? AS VARCHAR(200))"
                        + ")) AS s (");
        // One marker per column, nothing added to the bind order.
        long markers = sql.chars().filter(c -> c == '?').count();
        assertThat(markers).isEqualTo(3);
    }

    @Test
    void aColumnWithoutADeclarationFallsBackToAnUntypedMarker() {
        // ETL custom-SQL columns may have no source ColumnMeta; the statement shape must stay
        // usable rather than dropping the marker.
        String sql = db2.getUpsertSql(null, "T", List.of("ID", "NOTE"), List.of("ID"),
                Map.of("ID", Types.INTEGER, "NOTE", Types.VARCHAR),
                false, Map.of("ID", "INTEGER"));

        assertThat(sql).contains("USING (VALUES (CAST(? AS INTEGER), ?))");
    }

    @Test
    void theLegacyTypedOverloadKeepsUntypedMarkers() {
        String sql = db2.getUpsertSql(null, "T", List.of("ID", "FCS"), List.of("ID"),
                Map.of("ID", Types.INTEGER, "FCS", Types.VARCHAR), false);

        assertThat(sql).contains("USING (VALUES (?, ?))");
    }

    @Test
    void aKeylessTableStaysAPlainInsertWithUntypedMarkers() {
        // INSERT ... VALUES (?) resolves each marker from its own target column, so a cast is
        // unnecessary (and wrong when the target shape differs from the source mapping).
        String sql = db2.getUpsertSql(null, "NOKEY", List.of("A", "B"), List.of(),
                Map.of("A", Types.INTEGER, "B", Types.VARCHAR), false,
                Map.of("A", "INTEGER", "B", "VARCHAR(10)"));

        assertThat(sql).isEqualTo("INSERT INTO \"NOKEY\" (\"A\", \"B\") VALUES (?, ?)");
    }
}
