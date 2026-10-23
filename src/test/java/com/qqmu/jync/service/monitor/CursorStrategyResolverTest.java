package com.qqmu.jync.service.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * The cursor strategy determines whether updates are detectable at all, so these tests pin the
 * resolution order and — importantly — that a strategy which cannot see updates says so.
 */
class CursorStrategyResolverTest {

    private CursorStrategyResolver resolver;
    private SyncConfig config;

    @BeforeEach
    void setUp() {
        SyncProperties properties = new SyncProperties();
        properties.setFullCompareMaxRows(20000);
        resolver = new CursorStrategyResolver(properties);
        config = new SyncConfig();
    }

    private static ColumnMeta col(String name, int jdbcType) {
        ColumnMeta c = new ColumnMeta();
        c.setName(name);
        c.setJdbcType(jdbcType);
        c.setTypeName("T");
        return c;
    }

    private static TableMeta table(List<ColumnMeta> columns, List<String> pk, Long rowCount) {
        TableMeta t = new TableMeta();
        t.setName("orders");
        t.setColumns(columns);
        t.setPrimaryKeys(pk);
        t.setApproximateRowCount(rowCount);
        return t;
    }

    @Test
    void aLastModifiedTimestampIsPreferredAndDetectsUpdates() {
        TableMeta t = table(List.of(
                col("id", Types.INTEGER),
                col("create_time", Types.TIMESTAMP),
                col("update_time", Types.TIMESTAMP)), List.of("id"), 1000L);

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.TIMESTAMP);
        assertThat(strategy.getColumn()).isEqualTo("update_time");
        // This is the whole point of preferring it: updates are visible.
        assertThat(strategy.isMissesUpdates()).isFalse();
    }

    @Test
    void aCreationTimestampIsUsedButFlaggedAsMissingUpdates() {
        TableMeta t = table(List.of(
                col("id", Types.INTEGER),
                col("create_time", Types.TIMESTAMP)), List.of("id"), 1000L);

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.IDENTITY);
        assertThat(strategy.getColumn()).isEqualTo("create_time");
        // Silently pretending updates are covered would be the dangerous outcome.
        assertThat(strategy.isMissesUpdates()).isTrue();
        assertThat(strategy.getRationale()).containsIgnoringCase("updates");
    }

    @Test
    void aSingleNumericPrimaryKeyIsUsedWhenNoTimestampExists() {
        TableMeta t = table(List.of(
                col("id", Types.BIGINT),
                col("name", Types.VARCHAR)), List.of("id"), 1000L);

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.IDENTITY);
        assertThat(strategy.getColumn()).isEqualTo("id");
        assertThat(strategy.isMissesUpdates()).isTrue();
    }

    @Test
    void aCompositeKeyIsNotUsableAsACursor() {
        // Neither key column is monotonic on its own, so this must fall through.
        TableMeta t = table(List.of(
                col("tenant_id", Types.INTEGER),
                col("order_id", Types.INTEGER)), List.of("tenant_id", "order_id"), 100L);

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.FULL_COMPARE);
    }

    @Test
    void aSmallTableWithNoCursorFallsBackToFullComparison() {
        TableMeta t = table(List.of(
                col("code", Types.VARCHAR),
                col("label", Types.VARCHAR)), List.of("code"), 500L);

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.FULL_COMPARE);
        assertThat(strategy.isMissesUpdates()).isFalse();
    }

    @Test
    void aLargeTableWithNoCursorIsReportedRatherThanSilentlySkipped() {
        TableMeta t = table(List.of(
                col("code", Types.VARCHAR),
                col("label", Types.VARCHAR)), List.of("code"), 5_000_000L);

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.NONE);
        // The user needs to know why and what to do about it.
        assertThat(strategy.getRationale()).containsIgnoringCase("cursor column");
    }

    @Test
    void anExplicitlyConfiguredColumnOverridesAutoDetection() {
        TableMeta t = table(List.of(
                col("id", Types.INTEGER),
                col("update_time", Types.TIMESTAMP),
                col("audit_ts", Types.TIMESTAMP)), List.of("id"), 1000L);
        config.getCursorColumns().put("orders", "audit_ts");

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getColumn()).isEqualTo("audit_ts");
        assertThat(strategy.getRationale()).containsIgnoringCase("user");
    }

    @Test
    void aConfiguredColumnThatDoesNotExistFallsBackInsteadOfFailing() {
        // A stale config must not break the table's sync entirely.
        TableMeta t = table(List.of(
                col("id", Types.INTEGER),
                col("update_time", Types.TIMESTAMP)), List.of("id"), 1000L);
        config.getCursorColumns().put("orders", "no_such_column");

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.TIMESTAMP);
        assertThat(strategy.getColumn()).isEqualTo("update_time");
    }

    @Test
    void aTextColumnNamedLikeATimestampIsNotUsedAsATimestampCursor() {
        // Comparing a VARCHAR "update_time" with > is not reliably ordered, so the name alone
        // must not be enough to select it.
        TableMeta t = table(List.of(
                col("id", Types.BIGINT),
                col("update_time", Types.VARCHAR)), List.of("id"), 1000L);

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.IDENTITY);
        assertThat(strategy.getColumn()).isEqualTo("id");
    }

    // --- Oracle row-level SCN (ORA_ROWSCN) --------------------------------------------------

    @Test
    void oracleRowLevelScnIsUsedWhenNoLastModifiedColumnExists() {
        TableMeta t = table(List.of(
                col("id", Types.BIGINT),
                col("name", Types.VARCHAR)), List.of("id"), 1_000_000L);
        t.setRowLevelScn(true);

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.ROW_SCN);
        assertThat(strategy.getColumn()).isEqualTo("ORA_ROWSCN");
        assertThat(strategy.isIncremental()).isTrue();
        // The whole point of SCN over the integral PK that would otherwise be chosen:
        // updates to existing rows become visible.
        assertThat(strategy.isMissesUpdates()).isFalse();
        assertThat(strategy.getRationale()).contains("ROWDEPENDENCIES");
    }

    @Test
    void aLastModifiedColumnStillWinsOverRowScn() {
        // A real timestamp column is portable and indexable; MAX(ORA_ROWSCN) is a full scan.
        TableMeta t = table(List.of(
                col("id", Types.BIGINT),
                col("update_time", Types.TIMESTAMP)), List.of("id"), 1000L);
        t.setRowLevelScn(true);

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.TIMESTAMP);
        assertThat(strategy.getColumn()).isEqualTo("update_time");
    }

    @Test
    void aConfiguredCursorColumnStillWinsOverRowScn() {
        TableMeta t = table(List.of(
                col("id", Types.INTEGER),
                col("audit_ts", Types.TIMESTAMP)), List.of("id"), 1000L);
        t.setRowLevelScn(true);
        config.getCursorColumns().put("orders", "audit_ts");

        assertThat(resolver.resolve(t, config).getKind())
                .isEqualTo(CursorStrategy.Kind.TIMESTAMP);
    }

    @Test
    void blockLevelScnIsNotOfferedAsACursor() {
        // Without ROWDEPENDENCIES the SCN is tracked per block, so an unrelated row's update
        // bumps it for the whole block: too coarse to poll on. Falls through to the PK.
        TableMeta t = table(List.of(
                col("id", Types.BIGINT),
                col("name", Types.VARCHAR)), List.of("id"), 1000L);
        t.setRowLevelScn(false);

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.IDENTITY);
        assertThat(strategy.getColumn()).isEqualTo("id");
    }

    @Test
    void temporalAndIntegralTypeChecksCoverTheUsualJdbcTypes() {
        assertThat(CursorStrategy.isTemporalType(Types.TIMESTAMP)).isTrue();
        assertThat(CursorStrategy.isTemporalType(Types.DATE)).isTrue();
        assertThat(CursorStrategy.isTemporalType(Types.VARCHAR)).isFalse();

        assertThat(CursorStrategy.isIntegralType(Types.BIGINT)).isTrue();
        assertThat(CursorStrategy.isIntegralType(Types.NUMERIC)).isTrue();
        assertThat(CursorStrategy.isIntegralType(Types.VARCHAR)).isFalse();
    }

    @Test
    void sqlServerRowversionIsRecognizedAsCursorCandidate() {
        // mssql-jdbc reports rowversion / timestamp columns as Types.BINARY with TYPE_NAME
        // "timestamp". These must pass the candidate filter even though BINARY is not a
        // temporal or integral jdbc type.
        ColumnMeta rv = col("x", Types.BINARY);
        rv.setTypeName("timestamp");
        assertThat(CursorStrategy.isSqlServerRowversion(rv)).isTrue();
        assertThat(CursorStrategy.isCursorCandidate(rv)).isTrue();

        // A generic BINARY blob is not rowversion and must stay excluded.
        ColumnMeta blob = col("x", Types.BINARY);
        blob.setTypeName("VARBINARY");
        assertThat(CursorStrategy.isSqlServerRowversion(blob)).isFalse();
        assertThat(CursorStrategy.isCursorCandidate(blob)).isFalse();

        // VARBINARY with typeName "timestamp" — same column reported through a different
        // driver version — must still qualify.
        ColumnMeta rvVarbin = col("x", Types.VARBINARY);
        rvVarbin.setTypeName("timestamp");
        assertThat(CursorStrategy.isSqlServerRowversion(rvVarbin)).isTrue();
        assertThat(CursorStrategy.isCursorCandidate(rvVarbin)).isTrue();
    }

    @Test
    void sqlServerRowversionColumnIsAutoResolvedAsTimestampKind() {
        // A rowversion column named with the conventional ROWVERSION hint should be picked
        // as a TIMESTAMP-kind cursor (bumps on every UPDATE, so catches updates — unlike a
        // plain integral identity column).
        TableMeta t = new TableMeta();
        t.setName("T");
        t.setApproximateRowCount(1000L);
        ColumnMeta rv = new ColumnMeta();
        rv.setName("row_version");
        rv.setJdbcType(Types.BINARY);
        rv.setTypeName("timestamp");
        t.setColumns(List.of(rv));

        CursorStrategy strategy = resolver.resolve(t, config);

        assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.TIMESTAMP);
        assertThat(strategy.getColumn()).isEqualTo("row_version");
        // TIMESTAMP-kind must not be flagged as missing updates — rowversion bumps on UPDATE.
        assertThat(strategy.isMissesUpdates()).isFalse();
    }

    @Test
    void aLastModifiedColumnIsStillAutoDetectedUnderATurkishDefaultLocale() {
        // 默认 locale 的大写把 "update_time" 变成 "UPDATE_TİME"，与提示词常量比对失败：
        // 明明有 last-modified 列的表会被降级成整表对比或“无游标列”。
        TableMeta t = table(List.of(
                col("id", Types.INTEGER),
                col("update_time", Types.TIMESTAMP)), List.of("id"), 1000L);

        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            CursorStrategy strategy = resolver.resolve(t, config);
            assertThat(strategy.getKind()).isEqualTo(CursorStrategy.Kind.TIMESTAMP);
            assertThat(strategy.getColumn()).isEqualTo("update_time");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
