package com.synctool.service.converter;

import java.util.List;

import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.IndexMeta;
import com.synctool.dto.meta.ProcedureMeta;
import com.synctool.dto.meta.TableMeta;
import com.synctool.dto.meta.ViewMeta;
import com.synctool.model.DatabaseType;

/**
 * Generates DDL and DML in one product's dialect.
 *
 * <p>A dialect writes SQL <em>for the target</em>; it receives metadata that was read from
 * the source, so every method is also the place where source type names are translated
 * into target ones via {@link #mapType(ColumnMeta, DatabaseType)}.
 */
public interface SqlDialect {

    DatabaseType.DialectFamily family();

    /** Wraps an identifier so reserved words and mixed case survive. */
    String quoteIdentifier(String name);

    /** {@code schema.name}, or just {@code name} when no schema applies. */
    String qualify(String schema, String name);

    /**
     * Translates a source column's type into this dialect.
     *
     * @param column     the source column, including size and scale
     * @param sourceType the product the column was read from, since some mappings are
     *                   only correct for a specific source (e.g. Oracle NUMBER)
     */
    String mapType(ColumnMeta column, DatabaseType sourceType);

    String getCreateTableSql(TableMeta table, String targetSchema, String targetTable,
                             DatabaseType sourceType);

    /** DDL to add one column to an existing table. */
    String getAddColumnSql(String schema, String table, ColumnMeta column, DatabaseType sourceType);

    /** DDL to widen or otherwise change a column's type or nullability. */
    String getModifyColumnSql(String schema, String table, ColumnMeta column, DatabaseType sourceType);

    String getDropColumnSql(String schema, String table, String columnName);

    String getCreateIndexSql(IndexMeta index, String schema, String table);

    String getDropIndexSql(IndexMeta index, String schema, String table);

    /** {@code CREATE OR REPLACE VIEW} where supported, otherwise a drop-and-create pair. */
    List<String> getCreateViewSql(ViewMeta view, String schema, DatabaseType sourceType);

    List<String> getCreateProcedureSql(ProcedureMeta procedure, String schema, DatabaseType sourceType);

    String getDropTableSql(String schema, String table);

    String getDropViewSql(String schema, String view);

    String getDropProcedureSql(String schema, String procedure, boolean isFunction);

    /**
     * An idempotent insert-or-update statement — the core of safe re-delivery.
     *
     * <p>Because the sync cursor is only advanced after a successful target commit, a crash
     * or an overlap window can cause the same row to be presented twice. This statement
     * must therefore converge to the same final state whether it runs once or many times.
     *
     * @param pkColumns key columns used to detect an existing row; when empty the dialect
     *                  falls back to a plain insert and the caller must not rely on
     *                  idempotency
     * @return SQL with {@code ?} placeholders; the binding order is defined by
     *         {@link #upsertBindOrder(List, List)}
     */
    String getUpsertSql(String schema, String table, List<String> columns, List<String> pkColumns);

    /**
     * The order in which values must be bound to the statement from
     * {@link #getUpsertSql}. Dialects that repeat values (for example a MERGE that names
     * them in both the USING clause and the UPDATE clause) return a longer list.
     */
    List<String> upsertBindOrder(List<String> columns, List<String> pkColumns);

    /** {@code DELETE FROM t WHERE pk1 = ? AND pk2 = ?}. */
    String getDeleteByPkSql(String schema, String table, List<String> pkColumns);

    /** Paged read of a source table, used for the initial full load. */
    String getPaginationSql(String baseSql, long offset, int limit);

    /** Statement that disables foreign key enforcement, or null when unsupported. */
    String getDisableConstraintsSql();

    String getEnableConstraintsSql();

    String getTruncateSql(String schema, String table);

    /** Renders a literal for a DEFAULT clause, quoting only when the type needs it. */
    String renderDefaultValue(ColumnMeta column);

    /** True when this dialect can express the change as ALTER rather than a table rebuild. */
    boolean supportsAlterColumnType();
}
