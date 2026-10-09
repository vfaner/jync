package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * P0-6 (second half): the write path must arm explicit-value identity inserts
 * ({@code SET IDENTITY_INSERT ... ON}) before writing and disarm in the finally — only one
 * table per target session can be armed, and leaving it armed leaks the setting onto the
 * next table.
 */
class DataSyncServiceIdentityInsertTest {

    /** Dialect under test: claims the identity switch like SQL Server does. */
    private static class ProbeDialect extends GenericSqlDialect {
        @Override
        public String getSetIdentityInsertSql(String schema, String table, boolean on) {
            return "PROBE " + (on ? "ON" : "OFF");
        }
    }

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;
    private Statement probeStatement;

    @BeforeEach
    void setUp() throws Exception {
        source = DriverManager.getConnection("jdbc:h2:mem:idins_src;DB_CLOSE_DELAY=-1", "sa", "");
        Connection realTarget = DriverManager.getConnection(
                "jdbc:h2:mem:idins_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        // Spy the target so createStatement() (used only for the identity switch) is
        // intercepted: the probe SQL must not really run on H2.
        target = spy(realTarget);
        probeStatement = mock(Statement.class);
        doReturn(probeStatement).when(target).createStatement();

        service = new DataSyncService(new SyncProperties());
        SqlDialect probe = new ProbeDialect();
        ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(probe)
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        source.close();
        target.close();
    }

    @Test
    void identityInsertIsArmedBeforeWritingAndDisarmedAfter() throws Exception {
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        execRealTarget("CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(source, "INSERT INTO ITEMS VALUES (1,'a'), (2,'b')");

        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        id.setAutoIncrement(true);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);
        table.setColumns(List.of(id, name));

        DataSyncService.TableSyncResult result =
                service.syncTable(source, target, table,
                        CursorStrategy.identity("ID", Types.BIGINT, "numeric pk"),
                        new SyncProgress(), ctx);
        assertThat(result.isSuccess())
                .as("sync failed: %s", result.getError()).isTrue();
        assertThat(count("ITEMS")).isEqualTo(2);

        InOrder order = inOrder(probeStatement);
        order.verify(probeStatement).execute("PROBE ON");
        order.verify(probeStatement).execute("PROBE OFF");
    }

    @Test
    void aTableWithoutAnIdentityColumnNeverArmsTheSwitch() throws Exception {
        exec(source, "CREATE TABLE PLAIN (ID BIGINT PRIMARY KEY)");
        execRealTarget("CREATE TABLE PLAIN (ID BIGINT PRIMARY KEY)");
        exec(source, "INSERT INTO PLAIN VALUES (1)");

        TableMeta table = new TableMeta();
        table.setName("PLAIN");
        table.setPrimaryKeys(List.of("ID"));
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        // deliberately NOT auto-increment
        table.setColumns(List.of(id));

        DataSyncService.TableSyncResult result = service.syncTable(source, target, table,
                CursorStrategy.identity("ID", Types.BIGINT, "numeric pk"),
                new SyncProgress(), ctx);
        assertThat(result.isSuccess()).isTrue();

        org.mockito.Mockito.verify(probeStatement, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    private long count(String tableName) throws Exception {
        try (java.sql.PreparedStatement ps = target.prepareStatement(
                "SELECT COUNT(*) FROM " + tableName);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void exec(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** DDL must go through a real statement because createStatement on the spy is mocked. */
    private void execRealTarget(String sql) throws Exception {
        try (java.sql.PreparedStatement ps = target.prepareStatement(sql)) {
            ps.execute();
        }
    }
}
