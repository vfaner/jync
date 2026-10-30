package com.qqmu.jync.service.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * The data phase still loads parents before children when foreign keys stay enforced.
 *
 * <p>Table order comes from the foreign-key dictionary. A structure-off cycle used to read
 * metadata without that dictionary and then sort by it anyway, which silently reduced the
 * sort to "leave the source order alone". On a target whose dialect cannot switch constraint
 * enforcement off (Oracle and its look-alikes, PostgreSQL), the child then fails every row
 * with a parent-key violation on the first load. The FK dictionary is therefore still read
 * when, and only when, the load order actually matters.
 */
class SyncEngineForeignKeyOrderingTest {

    private static final long PROJECT_ID = 31L;

    private DataSourceManager dataSourceManager;
    private ChangeDetector changeDetector;
    private CursorStrategyResolver cursorResolver;
    private StructureSyncService structureSync;
    private DataSyncService dataSync;
    private SyncStateWriter stateWriter;
    private MetadataReader sourceReader;
    private MetadataReader targetReader;
    private SyncEngine engine;
    private Connection sourceConn;
    private Connection targetConn;
    private final DatabaseConfig sourceCfg = new DatabaseConfig();
    private final DatabaseConfig targetCfg = new DatabaseConfig();
    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        dataSourceManager = mock(DataSourceManager.class);
        changeDetector = mock(ChangeDetector.class);
        cursorResolver = mock(CursorStrategyResolver.class);
        structureSync = mock(StructureSyncService.class);
        dataSync = mock(DataSyncService.class);
        stateWriter = mock(SyncStateWriter.class);
        engine = new SyncEngine(dataSourceManager, changeDetector, cursorResolver, structureSync,
                dataSync, stateWriter, new SyncProperties());

        // No DB_CLOSE_DELAY: each test gets a fresh in-memory database, dropped when the
        // connections close in tearDown.
        sourceConn = DriverManager.getConnection("jdbc:h2:mem:fkorder_src", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:fkorder_tgt", "sa", "");
        when(dataSourceManager.getConnectionOrDefer(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnectionOrDefer(targetCfg)).thenReturn(targetConn);

        try (java.sql.Statement st = targetConn.createStatement()) {
            st.execute("CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY)");
            st.execute("CREATE TABLE ORDER_ITEMS (ID BIGINT PRIMARY KEY, ORDER_ID BIGINT)");
        }

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("fk-order");

        sourceReader = mock(MetadataReader.class);
        targetReader = mock(MetadataReader.class);
        // Child first in the source listing: only the FK-aware sort can put the parent first.
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(orderedMeta(false));
        when(sourceReader.readAllForDataWithForeignKeys(any(), any(), any(), any(), any(),
                eq(false), eq(false))).thenReturn(orderedMeta(true));
        when(sourceReader.estimateRowCount(any(), any(), anyString())).thenReturn(0L);
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS", "ORDER_ITEMS"));

        when(structureSync.logType(any())).thenAnswer(inv -> inv.getArgument(0));
        // The real comparator: the fixture's FK list must be what drives the order.
        when(structureSync.sortByDependency(any())).thenCallRealMethod();
        when(changeDetector.detect(eq(PROJECT_ID), any(), any(), any())).thenReturn(List.of());
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString()))
                .thenAnswer(inv -> new SyncProgress());
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> successfulTable());
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void aTargetThatCannotSwitchForeignKeysOffKeepsTheParentFirstOrder() throws Exception {
        engine.runCycle(ctx(new OracleDialect(11), DatabaseType.ORACLE, true));

        verify(sourceReader).readAllForDataWithForeignKeys(any(), any(), any(), any(), any(),
                eq(false), eq(false));
        verify(sourceReader, never()).readAllForData(any(), any(), any(), any(), any(),
                eq(false), eq(false));

        InOrder order = inOrder(dataSync);
        order.verify(dataSync).syncTable(any(), any(),
                argThat(t -> t != null && "ORDERS".equals(t.getName())), any(), any(), any());
        order.verify(dataSync).syncTable(any(), any(),
                argThat(t -> t != null && "ORDER_ITEMS".equals(t.getName())), any(), any(), any());
    }

    @Test
    void aTargetThatCanSwitchForeignKeysOffUsesTheReducedRead() throws Exception {
        engine.runCycle(ctx(new MySqlDialect(), DatabaseType.MYSQL, true));

        verify(sourceReader).readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false));
        verify(sourceReader, never()).readAllForDataWithForeignKeys(any(), any(), any(), any(),
                any(), eq(false), eq(false));
    }

    @Test
    void disablingTheConstraintSwitchOnAToggleableTargetStillReadsForeignKeys() throws Exception {
        engine.runCycle(ctx(new MySqlDialect(), DatabaseType.MYSQL, false));

        verify(sourceReader).readAllForDataWithForeignKeys(any(), any(), any(), any(), any(),
                eq(false), eq(false));
    }

    private SyncContext ctx(SqlDialect targetDialect, DatabaseType targetType,
                            boolean disableTargetConstraints) {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncData(true);
        config.setDisableTargetConstraints(disableTargetConstraints);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.MYSQL)
                .targetType(targetType)
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(new MySqlDialect())
                .targetDialect(targetDialect)
                .batchSize(50)
                .build();
    }

    private DatabaseMeta orderedMeta(boolean withForeignKeys) {
        TableMeta items = table("ORDER_ITEMS", "ID", "ORDER_ID");
        TableMeta orders = table("ORDERS", "ID");
        if (withForeignKeys) {
            TableMeta.ForeignKeyMeta fk = new TableMeta.ForeignKeyMeta();
            fk.setName("FK_ITEMS_ORDER");
            fk.setColumnName("ORDER_ID");
            items.setForeignKeys(List.of(fk));
        }
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(items, orders));
        return meta;
    }

    private DataSyncService.TableSyncResult successfulTable() {
        DataSyncService.TableSyncResult result = mock(DataSyncService.TableSyncResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(result.isSkipped()).thenReturn(false);
        when(result.isInitialLoad()).thenReturn(false);
        when(result.getRowsChanged()).thenReturn(0L);
        return result;
    }

    private static TableMeta table(String name, String... columnNames) {
        TableMeta table = new TableMeta();
        table.setName(name);
        for (int i = 0; i < columnNames.length; i++) {
            ColumnMeta column = new ColumnMeta();
            column.setName(columnNames[i]);
            column.setJdbcType(Types.BIGINT);
            column.setOrdinalPosition(i + 1);
            table.getColumns().add(column);
        }
        table.getPrimaryKeys().add(columnNames[0]);
        return table;
    }
}
