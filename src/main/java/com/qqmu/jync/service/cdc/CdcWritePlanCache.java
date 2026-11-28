package com.qqmu.jync.service.cdc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.sync.ColumnCleanser;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.util.CollectionUtil;

/**
 * Per-run cache of the source-layout-dependent half of a CDC write: the upsert statement,
 * its bind order, the column-to-JDBC-type map, DDL declarations, the emulated
 * update/insert pair, the delete-by-PK statement, and the table's column cleanser.
 *
 * <p>Every one of those is a pure function of the binding (column layout and primary key),
 * the target dialect and the run's configuration, yet before this cache each committed
 * source transaction rebuilt all of them: SQL string assembly, vendor type mapping and
 * column-rule regex compilation repeated once per transaction. A busy stream commits
 * thousands of transactions a second, so that rebuild was a measurable share of the apply
 * thread's work.
 *
 * <p>Scope is one consumer run — the cache is owned by
 * {@link CdcTargetTransactionFactory}, which lives exactly as long as the run. A DDL
 * rebuilds the binding ({@link CdcTableMetaSupplier#invalidate}), and the upsert plan is
 * keyed by binding identity plus the exact column list written: a new layout, a changed
 * primary key (which does not move the layout signature) and the column projection
 * switching on each get their own plan instead of a stale one. Correctness never rides on
 * eviction: a miss simply rebuilds.
 */
final class CdcWritePlanCache {

    /**
     * Bound on plans a pathological DDL loop may accumulate. Clearing is safe — the next
     * transaction rebuilds — and this stream should never get near it.
     */
    private static final int MAX_PLANS = 256;

    private final SyncContext ctx;
    private final Map<UpsertKey, UpsertPlan> upsertPlans = new ConcurrentHashMap<>();
    /** Keyed by binding identity: a rebuilt binding (DDL, type change) is a new key. */
    private final Map<CdcTableBinding, DeletePlan> deletePlans = new ConcurrentHashMap<>();
    private final Map<String, ColumnCleanser> cleansers = new ConcurrentHashMap<>();

    CdcWritePlanCache(SyncContext ctx) {
        this.ctx = ctx;
    }

    /** Column rules for one source table, compiled once per run. */
    ColumnCleanser cleanser(String table) {
        if (table == null) {
            return ColumnCleanser.forTable(ctx.getConfig(), null);
        }
        return cleansers.computeIfAbsent(table,
                name -> ColumnCleanser.forTable(ctx.getConfig(), name));
    }

    /** The upsert plan for this binding and the (possibly projected) column list. */
    UpsertPlan upsertPlan(CdcTableBinding binding, List<String> columns) {
        UpsertKey key = new UpsertKey(binding, columns);
        UpsertPlan plan = upsertPlans.get(key);
        if (plan != null) {
            return plan;
        }
        if (upsertPlans.size() >= MAX_PLANS) {
            upsertPlans.clear();
        }
        plan = buildUpsertPlan(binding, columns);
        upsertPlans.put(key, plan);
        return plan;
    }

    /** The delete-by-PK plan for this binding. */
    DeletePlan deletePlan(CdcTableBinding binding) {
        DeletePlan plan = deletePlans.get(binding);
        if (plan != null) {
            return plan;
        }
        if (deletePlans.size() >= MAX_PLANS) {
            deletePlans.clear();
        }
        plan = buildDeletePlan(binding);
        deletePlans.put(binding, plan);
        return plan;
    }

    private UpsertPlan buildUpsertPlan(CdcTableBinding binding, List<String> columns) {
        SqlDialect dialect = ctx.getTargetDialect();
        List<String> kept = List.copyOf(columns);
        List<String> pkColumns = List.copyOf(binding.pkNames());
        Map<String, Integer> types = immutable(binding.typeByColumn(
                dialect, ctx.effectiveSourceType()));
        if (!pkColumns.isEmpty() && dialect instanceof GenericSqlDialect generic
                && !generic.supportsNativeUpsert()) {
            return emulated(generic, binding, kept, pkColumns, types);
        }
        Map<String, String> declarations = immutable(binding.declarationByColumn(
                dialect, ctx.effectiveSourceType()));
        String sql = dialect.getUpsertSql(ctx.getTargetSchema(), binding.getTargetTable(),
                kept, pkColumns, types, false, declarations);
        List<String> bindOrder = List.copyOf(dialect.upsertBindOrder(kept, pkColumns));
        return new UpsertPlan(binding.getTargetTable(), kept, pkColumns, types,
                sql, bindOrder, false, null, List.of(), false);
    }

    /**
     * Update-then-insert fallback. Declarations cost nothing here: the emulated statements
     * carry no typed markers, so the CAST map is never built for this path.
     */
    private UpsertPlan emulated(GenericSqlDialect generic, CdcTableBinding binding,
                                List<String> columns, List<String> pkColumns,
                                Map<String, Integer> types) {
        // CDC transactions are replayed at-least-once after a reconnect; without the
        // "really differs" guard every replayed row rewrites identical values and can
        // file another audit entry. The polling writer uses the same guard for its
        // safety-margin replay.
        String updateSql = generic.getUpdateByPkSql(ctx.getTargetSchema(),
                binding.getTargetTable(), columns, pkColumns, true);
        String insertSql = generic.getUpsertSql(ctx.getTargetSchema(),
                binding.getTargetTable(), columns, List.of(), types);
        List<String> updateBindOrder = List.of();
        if (updateSql != null) {
            // SET clause values first, then the WHERE key values.
            List<String> order = new ArrayList<>(columns.size());
            for (String column : columns) {
                if (!CollectionUtil.containsIgnoreCase(pkColumns, column)) {
                    order.add(column);
                }
            }
            order.addAll(pkColumns);
            // The "really differs" condition compares each incoming value three times.
            for (String column : columns) {
                if (!CollectionUtil.containsIgnoreCase(pkColumns, column)) {
                    order.add(column);
                    order.add(column);
                    order.add(column);
                }
            }
            updateBindOrder = List.copyOf(order);
        }
        return new UpsertPlan(binding.getTargetTable(), columns, pkColumns, types,
                insertSql, List.of(), true, updateSql, updateBindOrder,
                updateSql != null);
    }

    private DeletePlan buildDeletePlan(CdcTableBinding binding) {
        SqlDialect dialect = ctx.getTargetDialect();
        List<String> pkColumns = List.copyOf(binding.pkNames());
        Map<String, Integer> types = immutable(binding.typeByColumn(
                dialect, ctx.effectiveSourceType()));
        String sql = dialect.getDeleteByPkSql(ctx.getTargetSchema(),
                binding.getTargetTable(), pkColumns);
        return new DeletePlan(pkColumns, types, sql);
    }

    private static <V> Map<String, V> immutable(Map<String, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /** The statements one binding keeps for the native (single upsert) path. */
    static final class UpsertPlan {

        final String targetTable;
        final List<String> columns;
        final List<String> pkColumns;
        final Map<String, Integer> types;
        /** The native upsert statement, or the plain INSERT when {@link #emulated}. */
        final String sql;
        final List<String> bindOrder;
        /** The target has no native upsert: run {@link #updateSql} then {@link #sql}. */
        final boolean emulated;
        /** Update-by-key statement of the emulated path; null for key-only tables. */
        final String updateSql;
        final List<String> updateBindOrder;
        /** The UPDATE carries the "really differs" predicate, so zero rows may mean no-op. */
        final boolean guardUnchanged;

        private UpsertPlan(String targetTable, List<String> columns, List<String> pkColumns,
                           Map<String, Integer> types, String sql, List<String> bindOrder,
                           boolean emulated, String updateSql, List<String> updateBindOrder,
                           boolean guardUnchanged) {
            this.targetTable = targetTable;
            this.columns = columns;
            this.pkColumns = pkColumns;
            this.types = types;
            this.sql = sql;
            this.bindOrder = bindOrder;
            this.emulated = emulated;
            this.updateSql = updateSql;
            this.updateBindOrder = updateBindOrder;
            this.guardUnchanged = guardUnchanged;
        }
    }

    /** The statement and bind types the delete-by-PK path needs. */
    static final class DeletePlan {

        final List<String> pkColumns;
        final Map<String, Integer> types;
        final String sql;

        private DeletePlan(List<String> pkColumns, Map<String, Integer> types, String sql) {
            this.pkColumns = pkColumns;
            this.types = types;
            this.sql = sql;
        }
    }

    /**
     * Binding identity plus the exact column list. Identity, not the layout signature: a
     * DDL that changes only a column's type keeps the signature but rebuilds the binding,
     * and the cached bind types must follow the new metadata.
     *
     * <p>The column list is held by reference; callers pass the binding's own names or a
     * freshly built projected list and never mutate it afterwards.
     */
    private static final class UpsertKey {

        private final CdcTableBinding binding;
        private final List<String> columns;
        private final int hash;

        private UpsertKey(CdcTableBinding binding, List<String> columns) {
            this.binding = binding;
            this.columns = columns;
            this.hash = 31 * System.identityHashCode(binding) + columns.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof UpsertKey)) {
                return false;
            }
            UpsertKey that = (UpsertKey) other;
            return binding == that.binding && columns.equals(that.columns);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
