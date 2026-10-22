package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The audit truncates its bound to the precision of the target's cursor column. On MySQL that
 * lookup must name the configured database the way Connector/J understands it — the database in
 * the CATALOG slot, schema null.
 *
 * <p>Measured with mysql:8 (Connector/J 8.0.33): {@code getColumns(null, "db_b", …)} returns
 * every database's same-named table, so a sibling in another database decides the precision.
 * The audit then keeps a fractional bound the target rounded away and reports a deficit on
 * every pass, clearing the cursor and reloading the table each time.
 */
class DataSyncServiceTargetCursorScaleTest {

    private Connection source;
    private DataSyncService service;
    private TableMeta table;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:scale_src;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE T_1 (ID BIGINT PRIMARY KEY, TS TIMESTAMP(6))");
        exec(source, "INSERT INTO T_1 (ID, TS) VALUES (1, TIMESTAMP '2026-01-01 00:00:00.5')");

        service = new DataSyncService(new SyncProperties());
        table = new TableMeta();
        table.setName("T_1");
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        source.close();
    }

    @Test
    void mysqlTargetReadsThePrecisionFromTheConfiguredDatabaseNotTheSchemaSlot() throws SQLException {
        Connection target = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet anyColumns = columns(0);
        ResultSet otherDatabase = columns(6);
        ResultSet configuredDatabase = columns(0);
        // Broad default first: Mockito lets the later, more specific stubbings win.
        when(target.getMetaData()).thenReturn(metaData);
        when(metaData.getColumns(any(), any(), anyString(), anyString()))
                .thenReturn(anyColumns);
        // The schema slot answers with ANOTHER database's T_1 (precision 6).
        when(metaData.getColumns(isNull(), eq("db_b"), eq("T_1"), eq("TS")))
                .thenReturn(otherDatabase);
        // The catalog slot answers with the configured database's T_1 (precision 0).
        when(metaData.getColumns(eq("db_b"), isNull(), eq("T_1"), eq("TS")))
                .thenReturn(configuredDatabase);
        PreparedStatement count = mock(PreparedStatement.class);
        ResultSet countRows = mock(ResultSet.class);
        when(target.prepareStatement(anyString())).thenReturn(count);
        when(count.executeQuery()).thenReturn(countRows);
        when(countRows.next()).thenReturn(true);
        when(countRows.getLong(1)).thenReturn(0L);

        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .targetType(DatabaseType.MYSQL)
                .targetSchema("db_b")
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .build();
        SyncProgress progress = new SyncProgress();
        progress.setLastSyncValue(JdbcRowMapper.cursorToString(
                Timestamp.valueOf("2026-01-01 00:00:00.5")));

        DataSyncService.RowCountAudit audit = service.auditSyncedRowCounts(source, target, table,
                CursorStrategy.timestamp("TS", Types.TIMESTAMP, "timestamp cursor"), progress,
                ctx);

        assertThat(audit).isNotNull();
        // With the configured database's precision (0) the bound is whole-second, the source
        // row at .5 drops out of the count, and the target is not falsely reported short.
        assertThat(audit.getSourceRows()).isZero();
        assertThat(audit.isTargetShort()).isFalse();
    }

    /** A one-row getColumns answer: the requested table and column, at the given precision. */
    private static ResultSet columns(int decimalDigits) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("TABLE_NAME")).thenReturn("T_1");
        when(rs.getString("COLUMN_NAME")).thenReturn("TS");
        when(rs.getInt("DECIMAL_DIGITS")).thenReturn(decimalDigits);
        return rs;
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
