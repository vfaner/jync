package com.synctool.service.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.synctool.config.SyncProperties;
import com.synctool.dto.SyncConfig;
import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.TableMeta;

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

    @Test
    void temporalAndIntegralTypeChecksCoverTheUsualJdbcTypes() {
        assertThat(CursorStrategy.isTemporalType(Types.TIMESTAMP)).isTrue();
        assertThat(CursorStrategy.isTemporalType(Types.DATE)).isTrue();
        assertThat(CursorStrategy.isTemporalType(Types.VARCHAR)).isFalse();

        assertThat(CursorStrategy.isIntegralType(Types.BIGINT)).isTrue();
        assertThat(CursorStrategy.isIntegralType(Types.NUMERIC)).isTrue();
        assertThat(CursorStrategy.isIntegralType(Types.VARCHAR)).isFalse();
    }
}
