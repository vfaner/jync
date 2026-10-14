package com.qqmu.jync.service.converter;

import java.util.List;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * Generates DDL and DML in one product's dialect.
 *
 * <p>A dialect writes SQL <em>for the target</em>; it receives metadata that was read from
 * the source, so every method is also the place where source type names are translated
 * into target ones via {@link #mapType(ColumnMeta, DatabaseType)}.
 */
public interface SqlDialect {

    DatabaseType.DialectFamily family();

    /**
     * When non-null, this dialect serves exactly that product, even though other products
     * share its {@link #family()} and have a generic family dialect of their own. Used for
     * a family member whose SQL diverges in one spot — e.g. openGauss shares the PostgreSQL
     * family but its default compatibility mode needs a MySQL-flavoured upsert.
     */
    default DatabaseType explicitType() {
        return null;
    }

    /**
     * The fetch size to use when streaming a large SELECT from a source of this dialect.
     *
     * <p>This is the one source-side concern a dialect carries: MySQL-protocol drivers
     * ignore ordinary fetch sizes and buffer the <em>entire</em> result set client-side,
     * so a full load of a big table becomes an out-of-memory risk unless the driver's
     * streaming sentinel is used. Drivers that honor plain fetch sizes keep the
     * configured value.
     */
    default int streamingFetchSize(int configured) {
        return configured;
    }

    /**
     * Statement that switches explicit-value inserts into an identity column for one table
     * ({@code SET IDENTITY_INSERT ... ON/OFF}); null when the product needs nothing. Only
     * SQL Server requires it — without it inserting the source's own id fails with error 544.
     */
    default String getSetIdentityInsertSql(String schema, String table, boolean on) {
        return null;
    }

    /**
     * Returns a dialect instance configured for the connected target server's major version,
     * or {@code this} when the dialect's SQL is version-independent. Used for products whose
     * identity syntax changed across releases (Oracle 12c introduced AS IDENTITY).
     */
    default SqlDialect withServerMajorVersion(Integer majorVersion) {
        return this;
    }

    /**
     * DDL that must run right after CREATE TABLE, one statement per list entry — e.g. the
     * sequence and trigger that emulate an identity column on products without native support.
     * Empty on every modern dialect.
     */
    default List<String> getPostCreateTableDdl(TableMeta table, String schema, String tableName,
                                               DatabaseType sourceType) {
        return List.of();
    }

    /**
     * DDL that must run after an ADD/MODIFY COLUMN, mirroring {@link #getPostCreateTableDdl}
     * for the single-column change path.
     */
    default List<String> getPostColumnChangeDdl(String schema, String table, ColumnMeta column,
                                                DatabaseType sourceType) {
        return List.of();
    }

    /**
     * DDL that advances the column's value generator so the next omitted-value insert lands on
     * {@code nextValue} (typically MAX(column) + 1 after an initial load). Empty for dialects
     * without a generator. Statements are best-effort: a failure is logged, not fatal.
     */
    default List<String> getIdentityReseedDdl(String schema, String table, ColumnMeta column,
                                              long nextValue) {
        return List.of();
    }

    /** {@code SELECT MAX("col") FROM "schema"."table"} in this dialect's quoting. */
    default String getMaxValueSql(String schema, String table, String column) {
        return "SELECT MAX(" + quoteIdentifier(column) + ") FROM " + qualify(schema, table);
    }

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
     * Typed variant: {@code columnJdbcTypes} maps a column name to the JDBC type of the
     * column the bind lands in, so dialects that need one can render an explicit cast on
     * the placeholder (openGauss/PostgreSQL reject a varchar bind into a TIME column
     * without {@code ::time}). A null map means plain {@code ?} placeholders, exactly
     * like the untyped variant.
     */
    default String getUpsertSql(String schema, String table, List<String> columns,
                                List<String> pkColumns,
                                java.util.Map<String, Integer> columnJdbcTypes) {
        return getUpsertSql(schema, table, columns, pkColumns);
    }

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

    /**
     * Scoped variant toggling constraints only on the given target tables.
     *
     * <p>Default keeps the unscoped form for toggles that are connection-session scoped
     * (MySQL's {@code FOREIGN_KEY_CHECKS}). Dialects whose toggle is persistent DDL
     * (SQL Server) override it so unrelated tables in a shared database are untouched.
     * Returns null when the table list is empty.
     */
    default String getDisableConstraintsSql(String schema, List<String> tables) {
        return getDisableConstraintsSql();
    }

    default String getEnableConstraintsSql(String schema, List<String> tables) {
        return getEnableConstraintsSql();
    }

    String getTruncateSql(String schema, String table);

    /** Renders a literal for a DEFAULT clause, quoting only when the type needs it. */
    String renderDefaultValue(ColumnMeta column);

    /** True when this dialect can express the change as ALTER rather than a table rebuild. */
    boolean supportsAlterColumnType();
}
