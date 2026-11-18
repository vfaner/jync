package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
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
import java.sql.Time;
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
    private PreparedStatement boundaryStatement;
    private ResultSet boundaryRows;

    @BeforeEach
    void setUp() throws SQLException {
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        metadata = mock(ResultSetMetaData.class);
        boundaryStatement = mock(PreparedStatement.class);
        boundaryRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString(),
                eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY)))
                .thenReturn(statement);
        // The resume boundary check is the only equality-filtered wrapper on the connection;
        // an empty result set lets every resume test get past it unless it says otherwise.
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains(" = ?")),
                eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY)))
                .thenReturn(boundaryStatement);
        when(boundaryStatement.executeQuery()).thenReturn(boundaryRows);
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
                "SELECT * FROM (SELECT id FROM src\n) jync_etl_src ORDER BY id",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        cursor.close();
    }

    @Test
    void addsTheResumeConditionAndBindsItByJdbcType() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceCursor cursor = source.query(task(EtlWriteMode.APPEND, "id"),
                new EtlCheckpointValue(Types.INTEGER, "3"));

        verify(connection).prepareStatement(
                "SELECT * FROM (SELECT id FROM src\n) jync_etl_src WHERE id > ? ORDER BY id",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        verify(statement).setLong(1, 3L);
        cursor.close();
    }

    /**
     * A resume key that is not unique loses data: the continuation filters {@code key > ?},
     * so every row sharing the checkpoint value that the previous run had not reached yet is
     * skipped without a trace. The guard must stop the run instead of copying a gap.
     */
    @Test
    void refusesToResumeWhenTheCheckpointKeyIsNotUnique() throws SQLException {
        when(boundaryRows.next()).thenReturn(true, true);
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlProgramException failure = catchThrowableOfType(
                () -> source.query(task(EtlWriteMode.APPEND, "id"),
                        new EtlCheckpointValue(Types.INTEGER, "3")),
                EtlProgramException.class);

        assertThat(failure).hasMessage("error.etl.resumeKey.duplicate:id");
    }

    @Test
    void checksTheCheckpointBoundaryWithAnEqualityQuery() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        source.query(task(EtlWriteMode.APPEND, "id"),
                new EtlCheckpointValue(Types.INTEGER, "3")).close();

        verify(connection).prepareStatement(
                "SELECT id FROM (SELECT id FROM src\n) jync_etl_src WHERE id = ?",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        verify(boundaryStatement).setMaxRows(2);
        verify(boundaryStatement).setLong(1, 3L);
    }

    @Test
    void aFreshRunDoesNotLookAtTheCheckpointBoundary() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        source.query(task(EtlWriteMode.APPEND, "id"), null).close();

        verify(connection, never()).prepareStatement(
                argThat(sql -> sql != null && sql.contains(" = ?")),
                eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY));
    }

    @Test
    void probeRejectsARepeatedResumeKeyBeforeTheRun() throws SQLException {
        ResultSet duplicates = mock(ResultSet.class);
        when(statement.executeQuery(contains("GROUP BY"))).thenReturn(duplicates);
        when(duplicates.next()).thenReturn(true);
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceProbe probe = source.probe(task(EtlWriteMode.APPEND, "id"), 15);

        assertThat(probe.issues())
                .filteredOn(issue -> "etl.precheck.resume.duplicate".equals(issue.code()))
                .extracting(EtlSourceIssue::level)
                .containsExactly(EtlPrecheckResult.Level.ERROR);
    }

    @Test
    void probeWarnsWhenTheDuplicateScanCannotFinish() throws SQLException {
        when(statement.executeQuery(contains("GROUP BY")))
                .thenThrow(new SQLException("query timeout"));
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceProbe probe = source.probe(task(EtlWriteMode.APPEND, "id"), 15);

        assertThat(probe.issues())
                .filteredOn(issue -> "etl.precheck.resume.unverified".equals(issue.code()))
                .extracting(EtlSourceIssue::level)
                .containsExactly(EtlPrecheckResult.Level.WARN);
    }

    @Test
    void anUnsetResumeKeyIsNotScannedForDuplicates() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        source.probe(task(EtlWriteMode.APPEND, null), 15);

        verify(statement, never()).executeQuery(contains("GROUP BY"));
    }

    /**
     * SQL Server rejects ORDER BY in a derived table unless TOP/OFFSET/FOR accompanies it.
     * The resumption wrapper moves the customer's SELECT into exactly that position, so a
     * redundant top-level order (the wrapper adds its own) has to go.
     */
    @Test
    void aSqlServerResumeDropsTheRedundantOrderByFromTheDerivedTable() throws SQLException {
        EtlTask task = task(EtlWriteMode.APPEND, "id");
        task.setSourceSql("SELECT id FROM src ORDER BY created_at");
        JdbcEtlSource source = source(DatabaseType.SQLSERVER);

        source.query(task, null).close();

        verify(connection).prepareStatement(
                "SELECT * FROM (SELECT id FROM src\n) jync_etl_src ORDER BY id",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    @Test
    void aDb2ResumeAlsoDropsTheRedundantOrderBy() throws SQLException {
        EtlTask task = task(EtlWriteMode.APPEND, "id");
        task.setSourceSql("SELECT id FROM src ORDER BY created_at");
        JdbcEtlSource source = source(DatabaseType.DB2);

        source.query(task, null).close();

        verify(connection).prepareStatement(
                "SELECT * FROM (SELECT id FROM src\n) jync_etl_src ORDER BY id",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    /**
     * A non-resumable run leaves the extraction unwrapped, so its precheck must not refuse a
     * WITH statement on SQL Server just because the zero-row wrapper would be invalid there.
     */
    @Test
    void aSqlServerCteProbeRunsTheStatementUnwrapped() throws SQLException {
        EtlTask task = task(EtlWriteMode.OVERWRITE, null);
        task.setSourceSql("WITH c AS (SELECT id FROM src) SELECT id FROM c");
        JdbcEtlSource source = source(DatabaseType.SQLSERVER);

        source.probe(task, 15);

        verify(statement).executeQuery(
                "WITH c AS (SELECT id FROM src) SELECT id FROM c");
        verify(statement).setMaxRows(1);
    }

    @Test
    void aWrappableSelectStillUsesTheZeroRowProbe() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.SQLSERVER);

        source.probe(task(EtlWriteMode.OVERWRITE, null), 15);

        verify(statement).executeQuery(
                "SELECT * FROM (SELECT id FROM src\n) jync_etl_src WHERE 1 = 0");
        verify(statement, never()).setMaxRows(1);
    }

    @Test
    void doesNotWrapAnOverwriteQuery() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceCursor cursor = source.query(task(EtlWriteMode.OVERWRITE, "id"), null);

        verify(connection).prepareStatement(SQL,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        cursor.close();
    }

    /**
     * The enum and entity javadoc and the form hint all restrict resume keys to APPEND and
     * RANGE_OVERWRITE. An upsert re-applies every result row by key, so a stored checkpoint
     * must neither filter the query nor bind a resume value — doing so would silently skip the
     * updates that are the whole point of the mode.
     */
    @Test
    void doesNotWrapAnUpsertQueryEvenWithAResumeKey() throws SQLException {
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceCursor cursor = source.query(task(EtlWriteMode.UPSERT, "id"),
                new EtlCheckpointValue(Types.INTEGER, "3"));

        verify(connection).prepareStatement(SQL,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        verify(statement, never()).setLong(1, 3L);
        assertThat(cursor.resumeKeyIndex()).isEqualTo(-1);
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
                "SELECT * FROM (SELECT id FROM src\n) jync_etl_src WHERE 1 = 0");
        assertThat(probe.columns()).extracting(EtlSourceColumn::name).containsExactly("id");
        assertThat(probe.resumeKeyIndex()).isEqualTo(1);
        assertThat(probe.issues()).extracting(EtlSourceIssue::code)
                .contains("etl.precheck.source.ok", "etl.precheck.resume.ok");
    }

    /**
     * The customer's SQL may end in a {@code --} line comment. Appending the wrapper on the
     * same line lets the comment swallow the closing paren and the alias, turning a valid
     * extraction into a syntax error at precheck time.
     */
    @Test
    void aTrailingLineCommentDoesNotSwallowTheResumableWrapper() throws SQLException {
        EtlTask task = task(EtlWriteMode.APPEND, "id");
        task.setSourceSql("SELECT id FROM src -- only live rows");
        JdbcEtlSource source = source(DatabaseType.H2);

        EtlSourceCursor cursor = source.query(task, null);

        verify(connection).prepareStatement(
                "SELECT * FROM (SELECT id FROM src -- only live rows\n)"
                        + " jync_etl_src ORDER BY id",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        cursor.close();
    }

    @Test
    void aTrailingLineCommentDoesNotSwallowTheProbeWrapper() throws SQLException {
        EtlTask task = task(EtlWriteMode.APPEND, "id");
        task.setSourceSql("SELECT id FROM src -- only live rows");
        JdbcEtlSource source = source(DatabaseType.H2);

        source.probe(task, 15);

        verify(statement).executeQuery(
                "SELECT * FROM (SELECT id FROM src -- only live rows\n) jync_etl_src"
                        + " WHERE 1 = 0");
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
    void aClobColumnIsReadAsTextInsteadOfADriverHandle() throws SQLException {
        // Oracle's getObject on a CLOB returns an oracle.sql.CLOB handle that is bound to
        // this ResultSet's connection; handing it to another connection's setObject is at
        // best driver-specific. The sync reader materializes LOBs for the same reason.
        when(metadata.getColumnType(1)).thenReturn(Types.CLOB);
        when(metadata.getColumnTypeName(1)).thenReturn("CLOB");
        String text = "x".repeat(5000);
        when(resultSet.getString(1)).thenReturn(text);
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.ORACLE)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo(text);
        verify(resultSet, never()).getObject(1);
        cursor.close();
    }

    @Test
    void aBlobColumnIsReadAsBytesInsteadOfADriverHandle() throws SQLException {
        when(metadata.getColumnType(1)).thenReturn(Types.BLOB);
        when(metadata.getColumnTypeName(1)).thenReturn("BLOB");
        byte[] data = new byte[4000];
        when(resultSet.getBytes(1)).thenReturn(data);
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.ORACLE)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isSameAs(data);
        verify(resultSet, never()).getObject(1);
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
    void aMysqlYearColumnIsReadAsTheIntegerItHolds() throws SQLException {
        // Connector/J reports YEAR as JDBC DATE while the value is an integer year, and the
        // target column is created as SMALLINT (YEAR only survives onto a MySQL target).
        // getObject hands back a java.sql.Date that an integer column cannot take; the sync
        // reader turns the year back into an int, and the ETL cursor must read it the same
        // way or every ETL task over a YEAR column fails on the target.
        when(metadata.getColumnType(1)).thenReturn(Types.DATE);
        when(metadata.getColumnTypeName(1)).thenReturn("YEAR");
        when(resultSet.getInt(1)).thenReturn(2024);
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.MYSQL)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo(2024);
        verify(resultSet, never()).getObject(1);
        cursor.close();
    }

    @Test
    void aPostgresJsonColumnIsReadAsTextForAnotherTarget() throws SQLException {
        // PgJDBC answers a json/jsonb column with a driver-private PGobject. A non-PG target
        // driver cannot infer a bind type for it, so the row fails at insert time. The target
        // column is created as text by the dialect, which is what the sync reader binds.
        when(metadata.getColumnType(1)).thenReturn(Types.OTHER);
        when(metadata.getColumnTypeName(1)).thenReturn("jsonb");
        when(resultSet.getString(1)).thenReturn("{\"a\":1}");
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.POSTGRESQL)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo("{\"a\":1}");
        verify(resultSet, never()).getObject(1);
        cursor.close();
    }

    @Test
    void aPostgresArrayColumnIsReadAsItsTextLiteral() throws SQLException {
        // A PG array arrives as a PgArray bound to the source connection; no target driver
        // accepts it. The "{1,2}" literal is what the text target column stores, and what
        // the sync reader already produces for the same column.
        when(metadata.getColumnType(1)).thenReturn(Types.ARRAY);
        when(metadata.getColumnTypeName(1)).thenReturn("_int4");
        when(resultSet.getString(1)).thenReturn("{1,2}");
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.POSTGRESQL)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo("{1,2}");
        verify(resultSet, never()).getObject(1);
        cursor.close();
    }

    @Test
    void aSqlServerDatetimeOffsetIsReadAsAPortableOffsetDateTime() throws SQLException {
        // mssql-jdbc answers getObject with microsoft.sql.DateTimeOffset, a driver-private
        // class no other target driver can bind. The sync reader runs every column through
        // JdbcValueNormalizer first; the copy path must too or the same row succeeds on the
        // sync path and fails or lands garbled here.
        when(metadata.getColumnType(1)).thenReturn(Types.TIMESTAMP_WITH_TIMEZONE);
        when(metadata.getColumnTypeName(1)).thenReturn("datetimeoffset");
        java.time.OffsetDateTime offset =
                java.time.OffsetDateTime.parse("2026-11-18T10:00:00+08:00");
        when(resultSet.getObject(1, java.time.OffsetDateTime.class)).thenReturn(offset);
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.SQLSERVER)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo(offset);
        verify(resultSet, never()).getObject(1);
        cursor.close();
    }

    @Test
    void anOracleXmlTypeIsReadAsItsText() throws SQLException {
        // XMLTYPE is a character large object; getObject hands back the driver's own type
        // (or throws NoClassDefFoundError without the xdb jar), while getString is the text
        // every target stores. Same read the sync path makes through the normalizer.
        when(metadata.getColumnType(1)).thenReturn(Types.SQLXML);
        when(metadata.getColumnTypeName(1)).thenReturn("XMLTYPE");
        when(resultSet.getString(1)).thenReturn("<order id=\"1\"/>");
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.ORACLE)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo("<order id=\"1\"/>");
        verify(resultSet, never()).getObject(1);
        cursor.close();
    }

    @Test
    void aSqlServerUniqueIdentifierIsReadAsText() throws SQLException {
        when(metadata.getColumnType(1)).thenReturn(Types.CHAR);
        when(metadata.getColumnTypeName(1)).thenReturn("uniqueidentifier");
        when(resultSet.getString(1)).thenReturn("A1B2C3D4-0000-0000-0000-000000000000");
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.SQLSERVER)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1))
                .isEqualTo("A1B2C3D4-0000-0000-0000-000000000000");
        verify(resultSet, never()).getObject(1);
        cursor.close();
    }

    @Test
    void aZoneAwareTimestampIsReadAsAnInstantNotTheDriverObject() throws SQLException {
        // Oracle's TIMESTAMP WITH TIME ZONE column answers getObject with an
        // oracle.sql.TIMESTAMPTZ bound to the source connection — a driver-private handle no
        // other target driver can bind. The sync reader reads the instant through
        // getTimestamp (see readValue), and the copy path must hand the target the same.
        when(metadata.getColumnType(1)).thenReturn(Types.TIMESTAMP_WITH_TIMEZONE);
        when(metadata.getColumnTypeName(1)).thenReturn("TIMESTAMP WITH TIME ZONE");
        Timestamp instant = Timestamp.valueOf("2026-11-18 10:00:00");
        when(resultSet.getTimestamp(1)).thenReturn(instant);
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.ORACLE)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isSameAs(instant);
        verify(resultSet, never()).getObject(1);
        cursor.close();
    }

    @Test
    void aMysqlTimeValueTheDriverCannotBuildFallsBackToItsLiteral() throws SQLException {
        // MySQL's TIME domain runs to ±838 hours, which no java.time type can carry:
        // Connector/J rejects those values while building the object. One such row would
        // fail the entire task before it is ever bound; the server literal is what the
        // sync reader keeps, and what a MySQL target takes back verbatim.
        when(metadata.getColumnType(1)).thenReturn(Types.TIME);
        when(metadata.getColumnTypeName(1)).thenReturn("TIME");
        when(resultSet.getObject(1)).thenThrow(new RuntimeException("hours out of range"));
        when(resultSet.getString(1)).thenReturn("-838:59:59");
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.MYSQL)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo("-838:59:59");
        cursor.close();
    }

    @Test
    void anOrdinaryTimeValueKeepsTheDriverObject() throws SQLException {
        // The literal fallback must not take over for values every target can bind as a
        // real temporal; those keep the driver's own object.
        when(metadata.getColumnType(1)).thenReturn(Types.TIME);
        when(metadata.getColumnTypeName(1)).thenReturn("TIME");
        Time value = Time.valueOf("12:34:56");
        when(resultSet.getObject(1)).thenReturn(value);
        when(resultSet.next()).thenReturn(true, false);
        EtlSourceCursor cursor = source(DatabaseType.MYSQL)
                .query(task(EtlWriteMode.APPEND, "id"), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isSameAs(value);
        verify(resultSet, never()).getString(1);
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
                "SELECT * FROM (SELECT id FROM t\n) jync_etl_src ORDER BY id",
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
                "SELECT * FROM (SELECT id FROM t\n) jync_etl_src WHERE id > ? ORDER BY id",
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
                "SELECT * FROM (SELECT id FROM t\n) jync_etl_src WHERE 1 = 0");
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
