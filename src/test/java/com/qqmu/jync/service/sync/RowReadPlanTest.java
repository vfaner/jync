package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.lob.LobReadPolicy;

/**
 * A sync reads the same table once per row, and until now every row re-derived two
 * per-column facts: the LOB read mode and whether the vendor-type normalizer applies. Both
 * are functions of the column only (type, declared size, name), so resolving them per row
 * allocated a lower-cased column name and a lower-cased type name per value — measured at
 * roughly 9% of a single-worker Oracle full load. {@code RowReadPlan} computes both once
 * per table; these tests pin that the precomputed answers behave exactly like the old
 * per-row ones.
 */
class RowReadPlanTest {

    @Test
    void aLargeDeclaredColumnIsStreamedExactlyAsThePolicySays() throws SQLException {
        // 10,000 declared characters over a threshold of 1024: the policy's large mode
        // applies, yet the 64-character value still fits and comes back inline.
        LobReadPolicy policy = LobReadPolicy.forTable("DOCS", LobReadPolicy.Mode.ONE_SHOT,
                1024L, 2048L, List.of("ID")).withDeclaredSizes(Map.of("BODY", 10_000L));
        RowReadPlan plan = RowReadPlan.of(List.of("ID", "BODY"),
                List.of(Types.INTEGER, Types.VARCHAR), List.of("NUMBER", "VARCHAR2"), policy);

        List<String> calls = new CopyOnWriteArrayList<>();
        ResultSet rs = recording(calls, 1, "x".repeat(64));

        Map<String, Object> row = JdbcRowMapper.readRowInto(
                rs, plan, DatabaseType.ORACLE, new LinkedHashMap<>(), null);

        assertThat(calls).contains("getCharacterStream:2");
        assertThat(calls).doesNotContain("getString:2");
        assertThat(row.get("BODY")).isEqualTo("x".repeat(64));
    }

    @Test
    void anOrdinaryTableNeverTakesAStreamGetter() throws SQLException {
        LobReadPolicy policy = LobReadPolicy.forTable("ITEMS", LobReadPolicy.Mode.ONE_SHOT,
                65536L, 1024L, List.of("ID"));
        RowReadPlan plan = RowReadPlan.of(List.of("ID", "NAME"),
                List.of(Types.INTEGER, Types.VARCHAR), List.of("NUMBER", "VARCHAR2"), policy);

        List<String> calls = new CopyOnWriteArrayList<>();
        ResultSet rs = recording(calls, 7, "name-7");

        Map<String, Object> row = JdbcRowMapper.readRowInto(
                rs, plan, DatabaseType.ORACLE, new LinkedHashMap<>(), null);

        assertThat(calls).noneMatch(c -> c.startsWith("getCharacterStream")
                || c.startsWith("getBinaryStream"));
        assertThat(row).containsEntry("ID", 7).containsEntry("NAME", "name-7");
    }

    @Test
    void thePrecomputedNormalizationMatchesThePerRowNormalizer() throws SQLException {
        // SQL Server DATETIMEOFFSET is normalized (getObject(..., OffsetDateTime.class));
        // VARCHAR2 is not. Both must come out of a planned read exactly as before.
        LobReadPolicy policy = LobReadPolicy.disabled();
        List<String> columns = List.of("ID", "STAMP", "NAME");
        List<Integer> types = List.of(Types.INTEGER,
                Types.TIMESTAMP_WITH_TIMEZONE, Types.VARCHAR);
        List<String> typeNames = List.of("INT", "DATETIMEOFFSET", "VARCHAR2");
        RowReadPlan plan = RowReadPlan.of(columns, types, typeNames, policy);

        java.time.OffsetDateTime stamp =
                java.time.OffsetDateTime.parse("2026-10-22T12:00:00+08:00");
        ResultSet rs = recording(new CopyOnWriteArrayList<>(), 1, stamp, "n");

        Map<String, Object> planned = JdbcRowMapper.readRowInto(
                rs, plan, DatabaseType.SQLSERVER, new LinkedHashMap<>(), null);
        Map<String, Object> perRow = JdbcRowMapper.readRowInto(
                rs, columns, types, typeNames, DatabaseType.SQLSERVER,
                new LinkedHashMap<>(), policy, null);

        assertThat(planned).isEqualTo(perRow);
        assertThat(planned).containsEntry("STAMP", stamp);
    }

    @Test
    void aMissingTypeNameIsNotTreatedAsAVendorType() throws SQLException {
        RowReadPlan plan = RowReadPlan.of(List.of("ID"), List.of(Types.INTEGER),
                java.util.Collections.singletonList(null), LobReadPolicy.disabled());

        ResultSet rs = recording(new CopyOnWriteArrayList<>(), 3);

        assertThat(JdbcRowMapper.readRowInto(rs, plan, DatabaseType.ORACLE,
                new LinkedHashMap<>(), null)).containsEntry("ID", 3);
    }

    /** A ResultSet that records the getter used per column and answers with fixed values. */
    private static ResultSet recording(List<String> calls, Object... values) {
        return (ResultSet) Proxy.newProxyInstance(
                RowReadPlanTest.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("get") && args != null && args.length > 0
                            && args[0] instanceof Integer) {
                        int idx = (Integer) args[0];
                        calls.add(name + ":" + idx);
                        Object value = values[idx - 1];
                        if ("getCharacterStream".equals(name)) {
                            return value == null ? null : new java.io.StringReader(value.toString());
                        }
                        if ("getString".equals(name)) {
                            return value == null ? null : value.toString();
                        }
                        if ("getInt".equals(name)) {
                            return value == null ? 0 : ((Number) value).intValue();
                        }
                        return value;
                    }
                    if ("wasNull".equals(method.getName())) {
                        return false;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }
}
