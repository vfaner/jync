package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.lob.LobBinder;

/**
 * The copy loop binds through {@link LobBinder} exactly as the sync writer does. Without it
 * an ETL row of a MySQL LONGTEXT bound as a plain {@code setObject(String)} fails on an
 * Oracle-family target with ORA-01461 once the value outgrows VARCHAR2's 4000 bytes (2000
 * for RAW/BLOB), and an empty string silently becomes NULL.
 */
class EtlRunnerLobBindTest {

    private final PreparedStatement insert = mock(PreparedStatement.class);
    private final Connection connection = mock(Connection.class);
    private final Clob clob = mock(Clob.class);
    private final Blob blob = mock(Blob.class);

    @BeforeEach
    void setUp() throws SQLException {
        when(insert.getConnection()).thenReturn(connection);
        when(connection.createClob()).thenReturn(clob);
        when(connection.createBlob()).thenReturn(blob);
    }

    @Test
    void textOneByteOverTheVarchar2CeilingGoesThroughATemporaryClob() throws SQLException {
        String value = "a".repeat(4001);

        EtlRunner.bindRow(insert, List.of(Types.CLOB), new Object[] {value},
                new OracleDialect(), LobBinder.NOOP);

        verify(clob).setString(1, value);
        verify(insert).setClob(1, clob);
        verify(insert, never()).setObject(anyInt(), any());
    }

    @Test
    void textOfExactlyTheVarchar2CeilingStaysInline() throws SQLException {
        String value = "a".repeat(4000);

        EtlRunner.bindRow(insert, List.of(Types.CLOB), new Object[] {value},
                new OracleDialect(), LobBinder.NOOP);

        verify(insert).setObject(1, value);
        verify(insert, never()).setClob(anyInt(), any(Clob.class));
    }

    @Test
    void anEmptyStringKeepsItsNonEmptyClobOnAnOracleTarget() throws SQLException {
        EtlRunner.bindRow(insert, List.of(Types.CLOB), new Object[] {""},
                new OracleDialect(), LobBinder.NOOP);

        verify(clob).setString(1, "");
        verify(insert).setClob(1, clob);
        verify(insert, never()).setObject(anyInt(), any());
    }

    @Test
    void binaryOneByteOverTheRawCeilingGoesThroughATemporaryBlob() throws SQLException {
        byte[] value = new byte[2001];

        EtlRunner.bindRow(insert, List.of(Types.BLOB), new Object[] {value},
                new OracleDialect(), LobBinder.NOOP);

        verify(blob).setBytes(1, value);
        verify(insert).setBlob(1, blob);
        verify(insert, never()).setObject(anyInt(), any());
    }

    @Test
    void binaryOfExactlyTheRawCeilingStaysInline() throws SQLException {
        byte[] value = new byte[2000];

        EtlRunner.bindRow(insert, List.of(Types.BLOB), new Object[] {value},
                new OracleDialect(), LobBinder.NOOP);

        // Bound as raw bytes (the sync writer's binary bind), not as an opaque object.
        verify(insert).setBytes(1, value);
        verify(insert, never()).setBlob(anyInt(), any(Blob.class));
    }

    @Test
    void aTargetWithoutACeilingBindsTheHugeValueInline() throws SQLException {
        String value = "a".repeat(100_000);

        EtlRunner.bindRow(insert, List.of(Types.LONGVARCHAR), new Object[] {value},
                new MySqlDialect(), LobBinder.NOOP);

        verify(insert).setObject(1, value);
        verify(connection, never()).createClob();
    }

    @Test
    void theTemporaryLobIsTrackedForReleaseAfterTheBatch() throws SQLException {
        LobBinder.Tracker tracker = mock(LobBinder.Tracker.class);

        EtlRunner.bindRow(insert, List.of(Types.CLOB), new Object[] {"a".repeat(4001)},
                new OracleDialect(), tracker);

        verify(tracker).track(clob);
    }

    @Test
    void aNullCarriesTheTargetMappedType() throws SQLException {
        EtlRunner.bindRow(insert, List.of(Types.CLOB), new Object[] {null},
                new OracleDialect(), LobBinder.NOOP);

        verify(insert).setNull(1, Types.CLOB);
        verify(insert, never()).setObject(anyInt(), any());
    }

    /**
     * A MySQL LONGTEXT does not report itself as a LOB to Oracle's eyes until the target
     * column is known: the dialect turns it into a CLOB, and the bind type must follow, or
     * the value over 4000 bytes never reaches the temporary-CLOB path.
     */
    @Test
    void aLongTextSourceColumnBindsAsClobOnAnOracleTarget() {
        List<EtlSourceColumn> columns = List.of(
                new EtlSourceColumn("c", Types.LONGVARCHAR, "LONGTEXT", null, null, true));

        assertThat(bindTypes(columns, DatabaseType.MYSQL, new OracleDialect()))
                .containsExactly(Types.CLOB);
    }

    @Test
    void aWideVarcharSourceColumnBindsAsClobBecauseItsTargetColumnIsAClob() {
        List<EtlSourceColumn> columns = List.of(
                new EtlSourceColumn("c", Types.VARCHAR, "VARCHAR", 12_000, null, true));

        assertThat(bindTypes(columns, DatabaseType.MYSQL, new OracleDialect()))
                .containsExactly(Types.CLOB);
    }

    @Test
    void aTargetWithoutATypedPlaceholderKeepsTheSourceType() {
        List<EtlSourceColumn> columns = List.of(
                new EtlSourceColumn("c", Types.LONGVARCHAR, "LONGTEXT", null, null, true));

        assertThat(bindTypes(columns, DatabaseType.MYSQL, new MySqlDialect()))
                .containsExactly(Types.LONGVARCHAR);
    }

    private static List<Integer> bindTypes(List<EtlSourceColumn> columns,
                                           DatabaseType sourceType, SqlDialect dialect) {
        return EtlRunner.targetBindTypes(columns, sourceType, dialect);
    }
}
