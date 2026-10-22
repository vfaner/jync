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
     * Whether a streamed read of this source needs the connection inside an explicit
     * transaction. The PostgreSQL driver buffers the whole result set when autocommit is
     * on, so its fetch size has no effect until a transaction is open; MySQL takes the
     * opposite route (a streaming sentinel in {@link #streamingFetchSize}) and everyone
     * else honors the plain fetch size.
     */
    default boolean requiresTransactionalSourceForStreaming() {
        return false;
    }

    /**
     * Whether a stream obtained from a result set stays readable after another column's
     * stream getter has been called on the same row. PostgreSQL, Oracle and MySQL keep the
     * streams independent; the SQL Server driver closes the previous stream as soon as the
     * next column's stream is requested, so a large value there must be drained into its
     * {@code LobValue} before the row mapper moves on to the next column.
     */
    default boolean streamsValidAcrossColumns() {
        return true;
    }

    /**
     * Largest string/byte value this dialect can bind inline; anything larger must be sent
     * as a LOB handle. Oracle's answer is 32767 because a {@code MERGE ... USING (SELECT ?)}
     * projection binds the placeholder as a LONG — not as the CLOB column it is assigned to —
     * and a LONG is capped at 32767 bytes, failing with ORA-01461 above it.
     */
    default int maxInlineBindBytes() {
        return Integer.MAX_VALUE;
    }

    /**
     * Whether a streamed LOB must first be copied into a server-side temporary LOB.
     * Oracle's MERGE projection binds the placeholder as a LONG, so
     * {@code setCharacterStream} hits the same 32767-byte ceiling as {@code setString};
     * {@code createClob()} + {@code setClob} is the only way to send a larger value.
     */
    default boolean requiresServerSideLob() {
        return false;
    }

    /**
     * Whether an empty string bound to a LOB column must be sent as an empty LOB rather than
     * an ordinary empty string. Oracle treats {@code ''} as NULL, so a NOT NULL CLOB column
     * would reject it with ORA-01400; an empty temporary CLOB keeps the value non-null.
     */
    default boolean bindsEmptyStringAsLob() {
        return false;
    }

    /**
     * Returns this dialect with the empty-string LOB rescue switched on or off, for
     * {@code sync.lob.oracle-empty-string-as-empty-clob}. Dialects that have no rescue
     * return themselves unchanged; the ones that do carry the choice into every instance
     * they derive (for example a version-specific copy rebuilt for a target server).
     */
    default SqlDialect withEmptyStringAsLob(boolean enabled) {
        return this;
    }

    /**
     * The JDBC type a value should be bound with for a column whose target type is
     * {@code effectiveType} (the mapped target type, not the source's). Dialects whose LOB
     * placeholders need to be typed override this; everyone else keeps the effective type.
     */
    default int bindJdbcType(ColumnMeta column, DatabaseType sourceType, int effectiveType) {
        return effectiveType;
    }

    /**
     * Whether a temporal target column must receive a real {@code java.sql} temporal value
     * instead of text. The row reader hands MySQL DATETIME over as the server's wall-clock
     * literal — Connector/J's {@code getTimestamp} would shift it across zones — and this
     * target products differ in what they can do with that text: PostgreSQL casts it
     * ({@code ?::timestamp}), MySQL/T-SQL/DB2 convert it implicitly, Oracle runs it through
     * the session's NLS format and rejects it (ORA-01843). Those answering {@code true}
     * get the literal parsed into a {@code java.sql.Timestamp}/{@code Date} before binding.
     */
    default boolean requiresTypedTemporalBind() {
        return false;
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

    /**
     * Whether an initial full load into a <em>confirmed-empty</em> target of this dialect may
     * write plain {@code INSERT} instead of the upsert. On an empty table the upsert's match
     * test can never fire, so it only adds per-row work; on a non-empty one the INSERT fails
     * with a unique violation, which is why the caller probes first and treats "could not
     * tell" as not empty.
     *
     * <p>True for the Oracle, MySQL and PostgreSQL families — the products whose upsert is a
     * per-row match/arbiter step a plain INSERT can skip.
     */
    default boolean supportsInsertFirstInitialLoad() {
        return false;
    }

    /**
     * One-row query that answers "does this target table have any rows?" for
     * {@link #supportsInsertFirstInitialLoad()}. The default LIMIT form fits the MySQL and
     * PostgreSQL families; products without LIMIT override it.
     */
    default String emptyTargetProbeSql(String schema, String table) {
        return "SELECT 1 FROM " + qualify(schema, table) + " LIMIT 1";
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

    /**
     * {@code ADD COLUMN} for a column of a known source table, so a target with whole-table
     * constraints (MySQL's row-size budget) can keep using the plan the table was created
     * with instead of re-mapping the column in isolation.
     */
    default String getAddColumnSql(String schema, String table, ColumnMeta column,
                                   DatabaseType sourceType, TableMeta sourceTable) {
        return getAddColumnSql(schema, table, column, sourceType);
    }

    /** DDL to widen or otherwise change a column's type or nullability. */
    String getModifyColumnSql(String schema, String table, ColumnMeta column, DatabaseType sourceType);

    /** {@link #getModifyColumnSql(String, String, ColumnMeta, DatabaseType)} with the table. */
    default String getModifyColumnSql(String schema, String table, ColumnMeta column,
                                      DatabaseType sourceType, TableMeta sourceTable) {
        return getModifyColumnSql(schema, table, column, sourceType);
    }

    String getDropColumnSql(String schema, String table, String columnName);

    /**
     * DDL that sets (or, with a null/blank comment, clears) an existing table's comment.
     *
     * <p>Null means the target cannot change a table comment without rebuilding the table,
     * or the product has no table comments at all; the caller then skips the update instead
     * of failing the whole structure sync over a cosmetic difference.
     */
    default String getAlterTableCommentSql(String schema, String table, String comment) {
        return null;
    }

    String getCreateIndexSql(IndexMeta index, String schema, String table);

    /**
     * {@code CREATE INDEX} for an index on a table whose columns may have been narrowed for
     * the target. Most products accept any column type in a key and ignore the table.
     *
     * <p>MySQL is the exception: a column this dialect narrowed to LONGTEXT needs a key
     * length, and only the table's shape says which columns those are — so the owning table
     * travels with the index.
     *
     * @return the DDL, or {@code null} when this target cannot create the index as the
     *         source defines it; the caller then skips the index instead of failing.
     */
    default String getCreateIndexSql(IndexMeta index, String schema, String table,
                                     TableMeta sourceTable, DatabaseType sourceType) {
        return getCreateIndexSql(index, schema, table);
    }

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
     * As the typed variant, plus {@code guardUnchangedRows}: the caller has read the target
     * columns' real types and vouched that every non-key one can be compared with {@code =}
     * and {@code <>}. Dialects that rewrite a matched row should then add a "something really
     * differs" condition, so that a replayed row that is already identical reports zero
     * affected rows — the way MySQL's upsert does by itself. It matters because a cursor with
     * a safety margin replays its last second every cycle, and without the condition each
     * replay is booked as a change.
     *
     * <p>Callers must not pass {@code true} on a hunch: on Oracle comparing a CLOB fails the
     * whole statement (ORA-22848), which is worse than an extra log line. The default keeps
     * the plain upsert, so a dialect that has not implemented the condition stays correct.
     */
    default String getUpsertSql(String schema, String table, List<String> columns,
                                List<String> pkColumns,
                                java.util.Map<String, Integer> columnJdbcTypes,
                                boolean guardUnchangedRows) {
        return getUpsertSql(schema, table, columns, pkColumns, columnJdbcTypes);
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

    /**
     * Whether {@link #getPaginationSql} can express a non-zero offset, which a keyless table's
     * OFFSET walk needs from its second page on. Dialects whose offset form needs a server
     * release they cannot verify at this point answer false: failing on page two — after page
     * one committed — is worse than keeping the classic single streamed read.
     */
    default boolean supportsOffsetPaging() {
        return true;
    }

    /**
     * Paged read that also projects exactly {@code columnRefs} in the pagination wrapper.
     *
     * <p>Most products page by appending {@code LIMIT/OFFSET} or {@code OFFSET/FETCH} to the
     * statement, so the default is {@link #getPaginationSql}. It matters only where the wrapper
     * adds a helper column that an outer {@code SELECT *} would surface: Oracle's ROWNUM form
     * does, and a leaked {@code RNUM_} column would enter the copy's INSERT column list and fail
     * every page with "column not found". Such dialects override this method.
     *
     * @param baseSql    statement selecting the same columns, already ordered
     * @param columnRefs quoted column references, in projection order
     */
    default String getProjectedPaginationSql(String baseSql, List<String> columnRefs,
                                             long offset, int limit) {
        return getPaginationSql(baseSql, offset, limit);
    }

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

    /**
     * The scoped toggle as individual statements, to be executed one per table.
     *
     * <p>A driver aborts the rest of a multi-statement batch on the first failure. That is
     * tolerable for a session-scoped toggle (MySQL's flags die with the connection) but not
     * for SQL Server's, which is persistent table metadata: one table whose ALTER fails would
     * leave every table after it with checks still on — or, on the re-enable path, still off
     * and unenforced long after the connection is gone. SQL Server therefore overrides this
     * with one statement per table.
     */
    default List<String> getConstraintToggleStatements(String schema, List<String> tables,
                                                      boolean disable) {
        String sql = disable ? getDisableConstraintsSql(schema, tables)
                : getEnableConstraintsSql(schema, tables);
        return sql == null || sql.isBlank() ? List.of() : List.of(sql);
    }

    /**
     * Whether this dialect can switch foreign-key enforcement off for the duration of a load,
     * in either the session-scoped or the per-table form.
     *
     * <p>When it cannot (Oracle and its look-alikes, PostgreSQL, ...), the data phase loads
     * with the target's foreign keys enforced, so the engine must load parents before children
     * and therefore keeps reading the source foreign-key dictionary.
     */
    default boolean supportsConstraintToggle() {
        String sql = getDisableConstraintsSql();
        return sql != null && !sql.isBlank();
    }

    /**
     * The product's identifier length limit, or 0 when it is unknown or unlimited.
     *
     * <p>Used to refuse a name before the target rejects it mid-load with an error that names
     * neither the mapping that produced the name nor the limit it broke. The unit is the one
     * the product documents: see {@link #identifierLimitInBytes()}.
     */
    default int maxIdentifierBytes() {
        return 0;
    }

    /**
     * Whether {@link #maxIdentifierBytes()} counts bytes rather than characters.
     *
     * <p>Oracle and PostgreSQL count bytes; MySQL and SQL Server document a character limit.
     * Measuring every product in bytes would refuse a legal multi-byte name — 22 CJK
     * characters already exceed MySQL's 64 "bytes" — and a false refusal blocks a table that
     * used to sync.
     */
    default boolean identifierLimitInBytes() {
        return true;
    }

    String getTruncateSql(String schema, String table);

    /** Renders a literal for a DEFAULT clause, quoting only when the type needs it. */
    String renderDefaultValue(ColumnMeta column);

    /** True when this dialect can express the change as ALTER rather than a table rebuild. */
    boolean supportsAlterColumnType();
}
