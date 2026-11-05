package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * A source that rejects window functions (MySQL 5.7, MariaDB before 10.2) must not be asked to
 * split its keys: the statement fails every time, and the per-table INFO that follows was log
 * noise on servers that can never answer. The dialect says so up front.
 */
@ExtendWith(OutputCaptureExtension.class)
class DataSyncServiceWindowFunctionGateTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private final DataSyncService dataSync = new DataSyncService(new SyncProperties());

    @Test
    void aSourceWithoutWindowFunctionsIsNotAskedToSplitKeys() throws SQLException {
        Connection connection = mock(Connection.class);

        List<List<Object>> boundaries = dataSync.sampleKeyBoundaries(connection, table(), IDENTITY,
                null, ctx(mysql57()), 4);

        assertThat(boundaries).isNull();
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void theDegradeIsAnnouncedOncePerProject(CapturedOutput output) throws SQLException {
        Connection connection = mock(Connection.class);
        SyncContext ctx = ctx(mysql57());

        dataSync.sampleKeyBoundaries(connection, table(), IDENTITY, null, ctx, 4);
        dataSync.sampleKeyBoundaries(connection, table(), IDENTITY, null, ctx, 4);

        assertThat(countOf(output.getAll(), "does not support window functions")).isEqualTo(1);
    }

    @Test
    void aSourceWithWindowFunctionsStillAttemptsTheSamplingQuery() throws SQLException {
        // The gate must not disable sampling for every product; a modern source keeps the
        // parallel full load.
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

        List<List<Object>> boundaries = dataSync.sampleKeyBoundaries(connection, table(), IDENTITY,
                null, ctx(new GenericSqlDialect()), 4);

        assertThat(boundaries).isNull();
        verify(connection).prepareStatement(anyString());
    }

    private static MySqlDialect mysql57() {
        return (MySqlDialect) new MySqlDialect().withServerMajorVersion(5)
                .forProduct(DatabaseType.MYSQL);
    }

    private static SyncContext ctx(SqlDialect dialect) {
        Project project = new Project();
        project.setId(7L);
        project.setName("orders");
        return SyncContext.builder()
                .project(project)
                .sourceType(DatabaseType.MYSQL)
                .sourceDialect(dialect)
                .build();
    }

    private static TableMeta table() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        id.setTypeName("BIGINT");
        id.setPrimaryKey(true);
        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        table.setColumns(new ArrayList<>(List.of(id)));
        table.setPrimaryKeys(List.of("ID"));
        return table;
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
