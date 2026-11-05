package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;

/**
 * The JDBC source adapter carries the extraction behavior that already worked: the wrapper
 * around the customer's SQL, the ORDER BY used for resumable runs, the checkpoint binding per
 * JDBC type, the MySQL streaming fetch size, and the zero-row probe. These are asserted against
 * mocks because a real database would show the rows but not the shape of the statement sent.
 */
class JdbcEtlSourceTest {

    private static final String SQL = "SELECT id FROM src";

    private Connection connection;
    private PreparedStatement statement;
    private ResultSet resultSet;
    private ResultSetMetaData metadata;

    @BeforeEach
    void setUp() throws SQLException {
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        metadata = mock(ResultSetMetaData.class);
        when(connection.prepareStatement(anyString(),
                eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY)))
                .thenReturn(statement);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);
        when(metadata.getColumnTypeName(1)).thenReturn("INTEGER");
        when(metadata.getPrecision(1)).thenReturn(10);
        when(metadata.getScale(1)).thenReturn(0);
        when(metadata.isNullable(1)).thenReturn(ResultSetMetaData.columnNullable);
        when(resultSet.findColumn("id")).thenReturn(1);
    }

    @Test
    void wrapsAndOrdersAResumableQuery() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceCursor cursor = source.query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.columns()).extracting(EtlSourceColumn::name).containsExactly("id");
        assertThat(cursor.resumeKeyIndex()).isEqualTo(1);
        verify(connection).prepareStatement(
                "SELECT * FROM (SELECT id FROM src) jync_etl_src ORDER BY id",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        cursor.close();
    }

    @Test
    void addsTheResumeConditionAndBindsItByJdbcType() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceCursor cursor = source.query(task(EtlWriteMode.APPEND, "id"),
                new EtlCheckpointValue(Types.INTEGER, "3"));

        verify(connection).prepareStatement(
                "SELECT * FROM (SELECT id FROM src) jync_etl_src WHERE id > ? ORDER BY id",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        verify(statement).setLong(1, 3L);
        cursor.close();
    }

    @Test
    void doesNotWrapAnOverwriteQuery() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceCursor cursor = source.query(task(EtlWriteMode.OVERWRITE, "id"), null);

        verify(connection).prepareStatement(SQL,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        cursor.close();
    }

    @Test
    void usesTheStreamingFetchSizeForMysql() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.MYSQL);

        EtlSourceCursor cursor = source.query(task(EtlWriteMode.OVERWRITE, null), null);

        verify(statement).setFetchSize(Integer.MIN_VALUE);
        cursor.close();
    }

    /**
     * MariaDB Connector/J 3.x throws "invalid fetch size" on the Connector/J sentinel;
     * only Connector/J itself needs it. MariaDB streams on the ordinary fetch size.
     */
    @Test
    void usesANormalFetchSizeForMariaDb() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.MARIADB);

        EtlSourceCursor cursor = source.query(task(EtlWriteMode.OVERWRITE, null), null);

        verify(statement).setFetchSize(1000);
        cursor.close();
    }

    @Test
    void usesANormalFetchSizeForEverythingElse() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceCursor cursor = source.query(task(EtlWriteMode.OVERWRITE, null), null);

        verify(statement).setFetchSize(1000);
        cursor.close();
    }

    @Test
    void appliesTheTaskTimeout() throws SQLException {
        EtlTask task = task(EtlWriteMode.OVERWRITE, null);
        task.setTimeoutSeconds(5);
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceCursor cursor = source.query(task, null);

        verify(statement).setQueryTimeout(5);
        cursor.close();
    }

    @Test
    void probeRunsTheZeroRowWrapperAndReportsTheResumeKey() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceProbe probe = source.probe(task(EtlWriteMode.APPEND, "id"), 15);

        verify(statement).executeQuery(
                "SELECT * FROM (SELECT id FROM src) jync_etl_src WHERE 1 = 0");
        assertThat(probe.columns()).extracting(EtlSourceColumn::name).containsExactly("id");
        assertThat(probe.resumeKeyIndex()).isEqualTo(1);
        assertThat(probe.issues()).extracting(EtlSourceIssue::code)
                .contains("etl.precheck.source.ok", "etl.precheck.resume.ok");
    }

    @Test
    void probeRejectsALabelTheTargetCouldNotReproduce() throws SQLException {
        when(metadata.getColumnLabel(1)).thenReturn("id + 1");
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceProbe probe = source.probe(task(EtlWriteMode.OVERWRITE, null), 15);

        assertThat(probe.columns()).isEmpty();
        EtlSourceIssue issue = probe.issues().stream()
                .filter(candidate -> "etl.precheck.column.error".equals(candidate.code()))
                .findFirst()
                .orElseThrow();
        assertThat(issue.level()).isEqualTo(EtlPrecheckResult.Level.ERROR);
        assertThat(issue.detail()).isEqualTo("id + 1");
    }

    @Test
    void probeReportsAMissingResumeKey() throws SQLException {
        when(resultSet.findColumn("not_there")).thenThrow(new SQLException("no such column"));
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceProbe probe = source.probe(task(EtlWriteMode.APPEND, "not_there"), 15);

        assertThat(probe.resumeKeyIndex()).isEqualTo(-1);
        EtlSourceIssue issue = probe.issues().stream()
                .filter(candidate -> "etl.precheck.resume.error".equals(candidate.code()))
                .findFirst()
                .orElseThrow();
        assertThat(issue.detail()).isEqualTo("not_there");
    }

    @Test
    void aMissingResumeKeyFailsTheQueryAsAProgramError() throws SQLException {
        when(resultSet.findColumn("not_there")).thenThrow(new SQLException("no such column"));
        JdbcEtlSource source = source(DatabaseType.H2);

        assertThatThrownBy(() -> source.query(task(EtlWriteMode.APPEND, "not_there"), null))
                .isInstanceOf(EtlProgramException.class)
                .hasMessage("resume key 'not_there' is not a column of the extraction query");
    }

    @Test
    void closingTheSourceClosesTheConnection() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        source.close();

        verify(connection).close();
    }

    @Test
    void closingTheCursorClosesTheResultSetAndTheStatement() throws SQLException {
        EtlSourceCursor cursor = source(DatabaseType.H2)
                .query(task(EtlWriteMode.OVERWRITE, null), null);

        cursor.close();

        verify(resultSet).close();
        verify(statement).close();
    }

    @Test
    void rowsAreReadThroughTheCursor() throws SQLException {
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(42);
        when(resultSet.getString(1)).thenReturn("42");
        EtlSourceCursor cursor = source(DatabaseType.H2)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo(42);
        assertThat(cursor.getString(1)).isEqualTo("42");
        assertThat(cursor.next()).isFalse();
        cursor.close();
    }

    @Test
    void anOracleSourceReadsAZoneLessTimestampAsAWallClock() throws SQLException {
        // Oracle DATE and TIMESTAMP both report Types.TIMESTAMP. getObject builds a
        // Timestamp by reading the wall clock in the JVM zone, and Asia/Shanghai has no
        // such local time on 1948-05-01 00:00, so the driver's instant lands on 01:00.
        when(metadata.getColumnType(1)).thenReturn(Types.TIMESTAMP);
        when(metadata.getColumnTypeName(1)).thenReturn("DATE");
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);
        when(resultSet.getObject(1, LocalDateTime.class)).thenReturn(wall);
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.ORACLE)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo(wall);
        verify(resultSet, never()).getObject(1);
        cursor.close();
    }

    @Test
    void aPostgresSourceReadsAZoneLessTimestampAsAWallClock() throws SQLException {
        // PgJDBC's plain getObject builds a Timestamp through the JVM zone too: a stored
        // 1948-05-01 00:00 comes back as 01:00. The typed getter returns the wall clock.
        when(metadata.getColumnType(1)).thenReturn(Types.TIMESTAMP);
        when(metadata.getColumnTypeName(1)).thenReturn("timestamp");
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);
        when(resultSet.getObject(1, LocalDateTime.class)).thenReturn(wall);
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.POSTGRESQL)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo(wall);
        verify(resultSet, never()).getObject(1);
        cursor.close();
    }

    @Test
    void aNonOracleSourceKeepsTheRawTimestampObject() throws SQLException {
        // The wall-clock read is only safe where the source is known to store one as a
        // wall clock; every other driver keeps the value getObject already returned.
        when(metadata.getColumnType(1)).thenReturn(Types.TIMESTAMP);
        when(metadata.getColumnTypeName(1)).thenReturn("TIMESTAMP");
        Timestamp raw = Timestamp.valueOf("1948-05-01 01:00:00");
        when(resultSet.getObject(1)).thenReturn(raw);
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.H2)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo(raw);
        verify(resultSet, never()).getObject(1, LocalDateTime.class);
        cursor.close();
    }

    @Test
    void aScriptRunsItsLeadingStatementsBeforeTheResumableQuery() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);
        EtlTask task = task(EtlWriteMode.APPEND, "id");
        task.setSourceSql("CREATE TEMP TABLE t (id INT); SELECT id FROM t");

        EtlSourceCursor cursor = source.query(task, null);

        // PostgreSQL refuses CREATE TEMP TABLE inside a read-only transaction, so a script
        // drops the hint; the source account's grants remain the guard.
        verify(connection).setReadOnly(false);
        verify(statement).execute("CREATE TEMP TABLE t (id INT)");
        verify(connection).prepareStatement(
                "SELECT * FROM (SELECT id FROM t) jync_etl_src ORDER BY id",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        cursor.close();
    }

    @Test
    void aScriptOverwriteRunsTheLastStatementUnwrapped() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);
        EtlTask task = task(EtlWriteMode.OVERWRITE, null);
        task.setSourceSql("SET @x = 1; SELECT id FROM t");

        EtlSourceCursor cursor = source.query(task, null);

        verify(statement).execute("SET @x = 1");
        verify(connection).prepareStatement("SELECT id FROM t",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        cursor.close();
    }

    @Test
    void aScriptResumeWrapsOnlyTheLastStatementAndBindsTheCheckpoint() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);
        EtlTask task = task(EtlWriteMode.APPEND, "id");
        task.setSourceSql("CREATE TEMP TABLE t (id INT); SELECT id FROM t");

        EtlSourceCursor cursor = source.query(task, new EtlCheckpointValue(Types.INTEGER, "3"));

        verify(statement).execute("CREATE TEMP TABLE t (id INT)");
        verify(connection).prepareStatement(
                "SELECT * FROM (SELECT id FROM t) jync_etl_src WHERE id > ? ORDER BY id",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        verify(statement).setLong(1, 3L);
        cursor.close();
    }

    @Test
    void probeRunsTheLeadingStatementsAndReportsTheScript() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);
        EtlTask task = task(EtlWriteMode.APPEND, "id");
        task.setSourceSql("CREATE TEMP TABLE t (id INT); SELECT id FROM t");

        EtlSourceProbe probe = source.probe(task, 15);

        verify(statement).execute("CREATE TEMP TABLE t (id INT)");
        verify(statement).executeQuery(
                "SELECT * FROM (SELECT id FROM t) jync_etl_src WHERE 1 = 0");
        assertThat(probe.issues()).extracting(EtlSourceIssue::code)
                .contains("etl.precheck.script.ok", "etl.precheck.source.ok");
        // The rollback is best-effort: MySQL-family DDL commits implicitly, so the precheck
        // may leave the source changed. The operator has to be told, not reassured.
        assertThat(probe.issues()).extracting(EtlSourceIssue::code)
                .contains("etl.precheck.script.sideEffects");
        assertThat(probe.issues()).filteredOn(
                        issue -> "etl.precheck.script.sideEffects".equals(issue.code()))
                .extracting(EtlSourceIssue::level)
                .containsExactly(EtlPrecheckResult.Level.WARN);
    }

    @Test
    void aScriptProbeRollsBackWhatItPrepared() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);
        EtlTask task = task(EtlWriteMode.APPEND, "id");
        task.setSourceSql("DELETE FROM staging; SELECT id FROM staging");

        source.probe(task, 15);

        // A precheck must not leave the source changed by the script it ran.
        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
    }

    @Test
    void aSingleStatementProbeKeepsTheConnectionAsBefore() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        source.probe(task(EtlWriteMode.APPEND, "id"), 15);

        verify(connection, never()).setAutoCommit(false);
        verify(connection, never()).rollback();
    }

    private JdbcEtlSource source(DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(1L);
        config.setType(type);
        return new JdbcEtlSource(connection, config);
    }

    private static EtlTask task(EtlWriteMode mode, String resumeKey) {
        EtlTask task = new EtlTask();
        task.setId(7L);
        task.setSourceSql(SQL);
        task.setTargetTable("dst");
        task.setWriteMode(mode);
        task.setResumeKeyColumn(resumeKey);
        return task;
    }
}
