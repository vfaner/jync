package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.OracleDialect;

/**
 * After an explicit-id bulk load the target's identity generator must be advanced past the
 * highest id, or the next insert omitting the id collides (ORA-00001). Pins the engine-side
 * wiring against both 12c identity and the pre-12 sequence+trigger emulation.
 */
class IdentityReseedTest {

    private Connection conn;
    private PreparedStatement maxPs;
    private ResultSet maxRs;
    private Statement ddlStmt;

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        maxPs = mock(PreparedStatement.class);
        maxRs = mock(ResultSet.class);
        ddlStmt = mock(Statement.class);
        when(conn.prepareStatement(anyString())).thenReturn(maxPs);
        when(maxPs.executeQuery()).thenReturn(maxRs);
        when(conn.createStatement()).thenReturn(ddlStmt);
    }

    private DataSyncService service() {
        return new DataSyncService(new SyncProperties());
    }

    private SyncContext oracleCtx(int majorVersion) {
        return SyncContext.builder()
                .targetType(DatabaseType.ORACLE)
                .targetDialect(new OracleDialect(majorVersion))
                .config(new SyncConfig())
                .build();
    }

    private TableMeta ordersTable() {
        TableMeta table = new TableMeta();
        table.setName("orders");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setAutoIncrement(true);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        table.getColumns().addAll(List.of(id, name));
        table.getPrimaryKeys().add("ID");
        return table;
    }

    private List<String> executedDdl() throws SQLException {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(ddlStmt, atLeastOnce()).execute(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void modernOracleRestartsIdentityAtMaxPlusOne() throws SQLException {
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getLong(1)).thenReturn(4096L);

        service().reseedIdentityAfterInitialLoad(conn, ordersTable(), oracleCtx(23));

        List<String> ddl = executedDdl();
        assertThat(ddl).hasSize(1);
        assertThat(ddl.get(0)).contains("ALTER TABLE \"ORDERS\"")
                .contains("MODIFY (\"ID\"")
                .contains("AS IDENTITY (START WITH 4097)");
    }

    @Test
    void legacyOracleRecreatesSequenceAndTriggerEvenWhenDropFindsNothing() throws SQLException {
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getLong(1)).thenReturn(99L);
        // A table prepared manually may have no sequence under our generated name.
        when(ddlStmt.execute(org.mockito.ArgumentMatchers.contains("DROP SEQUENCE")))
                .thenThrow(new SQLException("ORA-02289: sequence does not exist"));

        service().reseedIdentityAfterInitialLoad(conn, ordersTable(), oracleCtx(11));

        List<String> ddl = executedDdl();
        assertThat(ddl).hasSize(3);
        assertThat(ddl.get(0)).startsWith("DROP SEQUENCE");
        assertThat(ddl.get(1)).contains("CREATE SEQUENCE").contains("START WITH 100");
        assertThat(ddl.get(2)).contains("CREATE OR REPLACE TRIGGER").contains("BEFORE INSERT");
    }

    @Test
    void emptyTableTouchesNoGenerator() throws SQLException {
        when(maxRs.next()).thenReturn(true);
        when(maxRs.wasNull()).thenReturn(true);

        service().reseedIdentityAfterInitialLoad(conn, ordersTable(), oracleCtx(23));

        verify(ddlStmt, never()).execute(anyString());
    }

    @Test
    void nonOracleTargetsAreSkippedBeforeAnyQuery() throws SQLException {
        SyncContext mysqlCtx = SyncContext.builder()
                .targetType(DatabaseType.MYSQL)
                .config(new SyncConfig())
                .build();

        service().reseedIdentityAfterInitialLoad(conn, ordersTable(), mysqlCtx);

        verify(conn, never()).prepareStatement(anyString());
        verify(ddlStmt, never()).execute(anyString());
    }

    @Test
    void compositeKeyTableHasNoGeneratorToReseed() throws SQLException {
        TableMeta table = ordersTable();
        table.getPrimaryKeys().add("TENANT");

        service().reseedIdentityAfterInitialLoad(conn, table, oracleCtx(23));

        verify(conn, never()).prepareStatement(anyString());
    }

    @Test
    void aReseedFailureIsSwallowedNotThrown() throws SQLException {
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getLong(1)).thenReturn(5L);
        when(ddlStmt.execute(anyString())).thenThrow(new SQLException("ORA-54000"));

        service().reseedIdentityAfterInitialLoad(conn, ordersTable(), oracleCtx(23));
        // No exception escaping is the assertion.
    }
}
