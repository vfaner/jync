package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * HANA has no INFORMATION_SCHEMA. Its dictionaries hold what the generic reader cannot see:
 * SYS.VIEWS.DEFINITION (bare SELECT body) and SYS.PROCEDURES / SYS.FUNCTIONS.DEFINITION
 * (the full CREATE statement). Table and column comments already come through JDBC REMARKS,
 * so they are not re-read here.
 */
class HanaMetadataReaderTest {

    private final HanaMetadataReader reader = new HanaMetadataReader();

    @Test
    void onlyHanaIsClaimed() {
        assertThat(reader.supports(DatabaseType.HANA)).isTrue();
        assertThat(reader.supports(DatabaseType.MYSQL)).isFalse();
        assertThat(reader.supports(DatabaseType.CUSTOM)).isFalse();
    }

    @Test
    void aViewBodyComesFromSysViews() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet row = rs("SELECT \"ID\" FROM \"S\".\"T_USER\"");
        when(conn.prepareStatement(contains("SYS.VIEWS"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(row);

        ViewMeta view = reader.readView(conn, "S", "V_USER");

        assertThat(view.getDefinition()).isEqualTo("SELECT \"ID\" FROM \"S\".\"T_USER\"");
        verify(ps).setString(1, "V_USER");
        verify(ps).setString(2, "S");
    }

    @Test
    void aProcedureDefinitionComesFromSysProcedures() throws Exception {
        Connection conn = routineConnection("SYS.PROCEDURES",
                "CREATE PROCEDURE \"S\".\"P_ADD\" (IN a INTEGER, OUT c INTEGER) "
                        + "LANGUAGE SQLSCRIPT AS BEGIN c := :a; END");

        ProcedureMeta proc = reader.readProcedure(conn, "S", "P_ADD");

        assertThat(proc.getRoutineType()).isEqualTo("PROCEDURE");
        assertThat(proc.getDefinition()).startsWith("CREATE PROCEDURE");
    }

    @Test
    void aFunctionDefinitionComesFromSysFunctionsAndMarksTheRoutineAsFunction() throws Exception {
        Connection conn = routineConnection("SYS.FUNCTIONS",
                "CREATE FUNCTION \"S\".\"F_ADD\" (a INTEGER) RETURNS c INTEGER "
                        + "LANGUAGE SQLSCRIPT AS BEGIN c := :a; END");

        ProcedureMeta fn = reader.readProcedure(conn, "S", "F_ADD");

        assertThat(fn.getRoutineType()).isEqualTo("FUNCTION");
        assertThat(fn.getDefinition()).startsWith("CREATE FUNCTION");
    }

    /**
     * Stubs both dictionary lookups; only the named one returns a row, so the test pins which
     * dictionary the reader consulted rather than relying on call order.
     */
    private static Connection routineConnection(String dictionaryHoldingDefinition,
                                                String definition) throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        // Build every ResultSet before the when(...) it feeds: creating a mock inside
        // thenReturn() starts another stubbing and Mockito rejects the nesting.
        ResultSet noParameters = emptyRs();
        ResultSet hit = rs(definition);
        ResultSet miss = emptyRs();
        when(conn.getMetaData()).thenReturn(md);
        when(md.getProcedureColumns(any(), any(), any(), any())).thenReturn(noParameters);

        for (String dictionary : List.of("SYS.PROCEDURES", "SYS.FUNCTIONS")) {
            PreparedStatement ps = mock(PreparedStatement.class);
            when(ps.executeQuery()).thenReturn(
                    dictionary.equals(dictionaryHoldingDefinition) ? hit : miss);
            when(conn.prepareStatement(contains(dictionary))).thenReturn(ps);
        }
        return conn;
    }

    private static ResultSet emptyRs() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private static ResultSet rs(String value) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn(value);
        return rs;
    }
}
