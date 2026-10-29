package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * SQL Server CDC 的事务缓冲溢出落盘:落盘的是「写进目标端」的镜像。
 *
 * <p>修改前镜像(op=1 delete、op=3 update-before)只为 fold 提供主键,载荷根本不会被
 * 绑定;插入(op=2)与更新后镜像(op=4)才是整行落库的那两个。判定写成
 * {@code op == 1 || op == 4} 时两个方向都错:
 * <ul>
 *   <li>op=1 的载荷被写进落盘目录——一条 DELETE 几十万个带大文本的行会把
 *       spill-dir 写爆,而这些文件没有任何读者;</li>
 *   <li>op=2 的载荷留在堆里——批量 INSERT 大文本正是落盘要防的场景,事务字节上限
 *       看到的仍是原始大小,超限即 park。</li>
 * </ul>
 */
class SqlServerCdcConsumerLobSpillTest {

    private static final String H2_URL =
            "jdbc:h2:mem:mssql_lob_drain;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    private static final String FROM_LSN = "0x00000000000000000000";
    private static final String MAX_LSN = "0x0000002857ae025800ff";
    private static final String TX_LSN = "0x0000002857ae025800a0";

    @TempDir
    java.nio.file.Path spillDir;

    private Connection source;
    private SyncProperties properties;
    private int operation;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE docs (id INTEGER PRIMARY KEY, body CLOB)");
            st.execute("INSERT INTO docs (id, body) VALUES (1, 'old')");
        }
        properties = new SyncProperties();
        properties.getLob().setCdcSpillThresholdBytes(16);
        properties.getLob().setSpillDir(spillDir.toString());

        source = mock(Connection.class);

        PreparedStatement maxPs = mock(PreparedStatement.class);
        ResultSet maxRs = mock(ResultSet.class);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getString(1)).thenReturn(MAX_LSN);
        when(maxPs.executeQuery()).thenReturn(maxRs);
        when(source.prepareStatement(contains("fn_cdc_get_max_lsn"))).thenReturn(maxPs);

        PreparedStatement discoveryPs = mock(PreparedStatement.class);
        ResultSet discoveryRs = mock(ResultSet.class);
        when(discoveryRs.next()).thenReturn(true, false);
        when(discoveryRs.getString(1)).thenReturn("dbo");
        when(discoveryRs.getString(2)).thenReturn("docs");
        when(discoveryRs.getString(3)).thenReturn("dbo_docs");
        when(discoveryRs.getString(4)).thenReturn(FROM_LSN);
        when(discoveryPs.executeQuery()).thenReturn(discoveryRs);
        when(source.prepareStatement(contains("cdc.change_tables"))).thenReturn(discoveryPs);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void anInsertedPayloadLeavesTheHeapSoTheTransactionCapSeesOnlyItsHandle() throws Exception {
        // 上限 1 KB,而插入的载荷 4 KB(未落盘时字节预算按 2 字节/字符计)。落盘后
        // 行里只剩一个句柄,事务不该被判超限——这正是落盘存在的理由。
        properties.getCdc().setMaxTransactionBytes(1024L);
        scriptChange(2, "x".repeat(2000));

        consumer().drain(source, FROM_LSN);

        assertThat(readBody()).isEqualTo("x".repeat(2000));
    }

    @Test
    void aDeletePayloadIsNeverWrittenToTheSpillDirectory() throws Exception {
        // 落盘上限 8 字节:只要有人为删除镜像落盘,这一步就会炸。删除只绑主键,
        // 它的载荷没有任何读者,不该产生落盘文件。
        properties.getLob().setSpillMaxBytes(8L);
        scriptChange(1, "x".repeat(200));

        consumer().drain(source, FROM_LSN);

        assertThat(targetRowCount()).isZero();
    }

    // --- fixtures -------------------------------------------------------------------

    /** One CT row: column 1 = CONVERT(varchar, __$start_lsn), column 2 = __$operation. */
    private void scriptChange(int op, String body) throws SQLException {
        operation = op;
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(4);
        when(md.getColumnName(1)).thenReturn("__$start_lsn");
        when(md.getColumnName(2)).thenReturn("__$operation");
        when(md.getColumnName(3)).thenReturn("id");
        when(md.getColumnName(4)).thenReturn("body");

        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(md);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn(TX_LSN);
        when(rs.getInt(2)).thenAnswer(inv -> operation);
        when(rs.getObject("id")).thenReturn(1);
        when(rs.getObject("body")).thenReturn(body);

        PreparedStatement changesPs = mock(PreparedStatement.class);
        when(changesPs.executeQuery()).thenReturn(rs);
        when(source.prepareStatement(contains("fn_cdc_get_all_changes_"))).thenReturn(changesPs);
    }

    private SqlServerCdcConsumer consumer() throws SQLException {
        TableMeta meta = new TableMeta();
        meta.setName("docs");
        meta.setSchema("dbo");
        meta.getColumns().add(column("id", Types.INTEGER, "int", true));
        meta.getColumns().add(column("body", Types.LONGVARCHAR, "nvarchar", false));
        meta.getPrimaryKeys().add("id");
        MetadataReader reader = mock(MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(meta);

        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(100)
                .sourceReader(reader)
                .connectionProvider(cfg -> {
                    try {
                        return h2conn();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getConnection(any())).thenReturn(source);
        Project project = new Project();
        project.setId(1L);
        project.setName("mssql-lob");
        return new SqlServerCdcConsumer(project, mock(SyncLockService.LockHandle.class), ctx,
                mock(CdcPositionStore.class), mock(CdcBaselineService.class),
                mock(SyncTaskStore.class), mock(SyncStateWriter.class), dataSourceManager,
                new CdcRuntimeStats(), properties);
    }

    private String readBody() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT body FROM docs WHERE id = 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private long targetRowCount() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM docs")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static ColumnMeta column(String name, int jdbcType, String typeName, boolean pk) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setTypeName(typeName);
        column.setPrimaryKey(pk);
        return column;
    }

    private static Connection h2conn() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }
}
