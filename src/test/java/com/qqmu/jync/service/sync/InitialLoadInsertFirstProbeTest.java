package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.Db2Dialect;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OpenGaussDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlServerDialect;

/**
 * The initial load may write a plain {@code INSERT} instead of the dialect's upsert when the
 * target table is still empty — on an empty table nothing can match, so the upsert's match
 * test buys nothing while costing per-row work (the Oracle benchmark measured INSERT at
 * roughly 2.5x the MERGE throughput).
 *
 * <p>The decision is a one-row probe, and every way the probe can be wrong has to land on the
 * upsert: a table with rows, a target whose dialect has no such shortcut, a keyless table
 * (already INSERT-only), the switch turned off, or a probe that simply fails. INSERT into a
 * non-empty table raises a unique violation, so guessing "empty" is not a harmless mistake.
 */
class InitialLoadInsertFirstProbeTest {

    private Connection target;
    private Statement statement;
    private ResultSet probe;
    private DatabaseMetaData metadata;
    private ResultSet indexRows;
    private DataSyncService service;

    @BeforeEach
    void setUp() throws SQLException {
        target = mock(Connection.class);
        statement = mock(Statement.class);
        probe = mock(ResultSet.class);
        metadata = mock(DatabaseMetaData.class);
        indexRows = mock(ResultSet.class);
        when(target.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(probe);
        when(probe.next()).thenReturn(false);
        when(target.getMetaData()).thenReturn(metadata);
        when(metadata.getIndexInfo(any(), any(), anyString(), eq(false), eq(true)))
                .thenReturn(indexRows);
        // A plain primary key: the INSERT shortcut only defers to the upsert when the
        // target carries a unique index the primary key does not imply.
        stubIndexRows(new IndexRow("PRIMARY", "ID", true));
        service = new DataSyncService(new SyncProperties());
    }

    @Test
    void anEmptyOracleTargetIsLoadedWithInsert() throws SQLException {
        assertThat(useFirst(target, DatabaseType.ORACLE, new OracleDialect(),
                "T_ORDERS", List.of("ID"))).isTrue();

        assertThat(probedSql())
                .isEqualTo("SELECT 1 FROM \"APP\".\"T_ORDERS\" WHERE ROWNUM = 1");
    }

    @Test
    void anEmptyMySqlTargetIsLoadedWithInsert() throws SQLException {
        assertThat(useFirst(target, DatabaseType.MYSQL, new MySqlDialect(),
                "T_ORDERS", List.of("ID"))).isTrue();

        assertThat(probedSql()).isEqualTo("SELECT 1 FROM `APP`.`T_ORDERS` LIMIT 1");
    }

    @Test
    void anEmptyPostgresTargetIsLoadedWithInsert() throws SQLException {
        assertThat(useFirst(target, DatabaseType.POSTGRESQL, new PostgresDialect(),
                "T_ORDERS", List.of("ID"))).isTrue();

        assertThat(probedSql()).isEqualTo("SELECT 1 FROM \"APP\".\"T_ORDERS\" LIMIT 1");
    }

    @Test
    void anEmptyOpenGaussTargetIsLoadedWithInsert() throws SQLException {
        // openGauss shares the PostgreSQL family and inherits the same probe.
        assertThat(useFirst(target, DatabaseType.OPENGAUSS, new OpenGaussDialect(),
                "T_ORDERS", List.of("ID"))).isTrue();

        assertThat(probedSql()).isEqualTo("SELECT 1 FROM \"APP\".\"T_ORDERS\" LIMIT 1");
    }

    @Test
    void theSqlServerAndDb2ProbesUseTheirOwnRowLimitingSyntax() {
        // The keyless OFFSET walk probes the target on every dialect, including those without
        // the INSERT shortcut, so each needs a valid one-row query: neither has LIMIT.
        assertThat(new SqlServerDialect().emptyTargetProbeSql("APP", "T_ORDERS"))
                .isEqualTo("SELECT TOP 1 1 FROM [APP].[T_ORDERS]");
        assertThat(new Db2Dialect().emptyTargetProbeSql("APP", "T_ORDERS"))
                .isEqualTo("SELECT 1 FROM \"APP\".\"T_ORDERS\" FETCH FIRST 1 ROW ONLY");
    }

    @Test
    void dialectsWhoseOffsetNeedsAnUnverifiableServerReleaseCannotPageKeylessTables() {
        // The keyless walk needs a non-zero offset from page two on; a dialect that cannot
        // promise it must keep the classic streamed read rather than fail mid-load.
        assertThat(new SqlServerDialect().supportsOffsetPaging()).isFalse();
        assertThat(new Db2Dialect().supportsOffsetPaging()).isFalse();
        assertThat(new MySqlDialect().supportsOffsetPaging()).isTrue();
        assertThat(new OracleDialect().supportsOffsetPaging()).isTrue();
    }

    @Test
    void aUniqueIndexBeyondThePrimaryKeyKeepsTheUpsert() throws SQLException {
        // Two source rows may repeat (SHOP_ORDER_ID, ORDER_TYPE) under different primary
        // keys; the plain INSERT would abort the batch on the second row (MySQL 1062) while
        // the upsert absorbs the collision as it already does on a non-empty target.
        stubIndexRows(
                new IndexRow("PRIMARY", "ID", true),
                new IndexRow("UK_ORDERS_NAME", "NAME", true));

        assertThat(useFirst(target, DatabaseType.MYSQL, new MySqlDialect(),
                "T_ORDERS", List.of("ID"))).isFalse();
        // MySQL keeps the database in the JDBC catalog and reports no schema.
        verify(metadata).getIndexInfo(eq("APP"), isNull(), eq("T_ORDERS"), eq(false), eq(true));
    }

    @Test
    void aUniqueIndexThatIncludesThePrimaryKeyDoesNotDisableTheInsert() throws SQLException {
        // A duplicate on (ID, NAME) implies a duplicate ID, so the INSERT would have failed
        // on the primary key anyway — this index adds no new failure mode.
        stubIndexRows(
                new IndexRow("PRIMARY", "ID", true),
                new IndexRow("UK_ORDERS_ID_NAME", "ID", true),
                new IndexRow("UK_ORDERS_ID_NAME", "NAME", true));

        assertThat(useFirst(target, DatabaseType.MYSQL, new MySqlDialect(),
                "T_ORDERS", List.of("ID"))).isTrue();
    }

    @Test
    void aPostgresTargetKeepsTheInsertEvenWithASecondaryUniqueIndex() throws SQLException {
        // PostgreSQL's ON CONFLICT (pk) raises where the plain INSERT would too, so the
        // upsert absorbs nothing extra there: the shortcut costs no correctness and stays.
        stubIndexRows(
                new IndexRow("PRIMARY", "ID", true),
                new IndexRow("UK_ORDERS_NAME", "NAME", true));

        assertThat(useFirst(target, DatabaseType.POSTGRESQL, new PostgresDialect(),
                "T_ORDERS", List.of("ID"))).isTrue();
    }

    @Test
    void unreadableIndexMetadataFallsBackToTheUpsert() throws SQLException {
        when(target.getMetaData()).thenThrow(new SQLException("dictionary unavailable"));

        assertThat(useFirst(target, DatabaseType.MYSQL, new MySqlDialect(),
                "T_ORDERS", List.of("ID"))).isFalse();
    }

    @Test
    void aTargetThatAlreadyHasRowsStaysOnTheUpsert() throws SQLException {
        when(probe.next()).thenReturn(true);
        assertThat(useFirst(target, DatabaseType.MYSQL, new MySqlDialect(),
                "T_ORDERS", List.of("ID"))).isFalse();
    }

    @Test
    void aProbeThatFailsFallsBackToTheUpsert() throws SQLException {
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("ORA-00942"));
        assertThat(useFirst(target, DatabaseType.ORACLE, new OracleDialect(),
                "T_ORDERS", List.of("ID"))).isFalse();
    }

    @Test
    void everyOtherTargetKeepsItsOwnUpsert() throws SQLException {
        assertThat(useFirst(target, DatabaseType.SQLSERVER, new SqlServerDialect(),
                "T_ORDERS", List.of("ID"))).isFalse();
        verify(target, never()).createStatement();
    }

    @Test
    void theSwitchTurnsThePathOffWithoutProbing() throws SQLException {
        SyncProperties properties = new SyncProperties();
        properties.setInitialLoadInsertFirst(false);
        DataSyncService disabled = new DataSyncService(properties);

        assertThat(disabled.useInsertFirst(target, context(DatabaseType.MYSQL,
                new MySqlDialect()), "T_ORDERS", List.of("ID"))).isFalse();
        verify(target, never()).createStatement();
    }

    @Test
    void theOldOracleOnlySwitchStillBinds() throws SQLException {
        SyncProperties properties = new SyncProperties();
        properties.setOracleInitialLoadInsertFirst(false);
        DataSyncService disabled = new DataSyncService(properties);

        assertThat(properties.isInitialLoadInsertFirst()).isFalse();
        assertThat(disabled.useInsertFirst(target, context(DatabaseType.MYSQL,
                new MySqlDialect()), "T_ORDERS", List.of("ID"))).isFalse();
        verify(target, never()).createStatement();
    }

    @Test
    void aKeylessTableIsAlreadyInsertOnly() throws SQLException {
        assertThat(useFirst(target, DatabaseType.POSTGRESQL, new PostgresDialect(),
                "T_ORDERS", List.of())).isFalse();
        verify(target, never()).createStatement();
    }

    private record IndexRow(String index, String column, boolean unique) {
    }

    /** Serves {@code rows} from the index-info ResultSet, one per index column. */
    private void stubIndexRows(IndexRow... rows) throws SQLException {
        int[] cursor = {-1};
        when(indexRows.next()).thenAnswer(invocation -> ++cursor[0] < rows.length);
        when(indexRows.getShort("TYPE")).thenReturn(DatabaseMetaData.tableIndexOther);
        when(indexRows.getString(anyString())).thenAnswer(invocation -> {
            IndexRow row = rows[cursor[0]];
            String field = invocation.getArgument(0);
            return switch (field) {
                case "INDEX_NAME" -> row.index();
                case "COLUMN_NAME" -> row.column();
                case "TABLE_NAME" -> "T_ORDERS";
                default -> null;
            };
        });
        when(indexRows.getBoolean("NON_UNIQUE")).thenAnswer(invocation -> !rows[cursor[0]].unique());
    }

    private String probedSql() throws SQLException {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).executeQuery(sql.capture());
        return sql.getValue();
    }

    private boolean useFirst(Connection conn, DatabaseType targetType, SqlDialect dialect,
                             String table, List<String> pkColumns) {
        return service.useInsertFirst(conn, context(targetType, dialect), table, pkColumns);
    }

    private static SyncContext context(DatabaseType targetType, SqlDialect dialect) {
        return SyncContext.builder()
                .config(new SyncConfig())
                .targetType(targetType)
                .targetDialect(dialect)
                .targetSchema("APP")
                .batchSize(50)
                .build();
    }
}
