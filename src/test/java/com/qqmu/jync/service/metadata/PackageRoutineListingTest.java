package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Oracle package members reported as {@code PKG.PROC} must not be listed as standalone
 * routines.
 *
 * <p>The old behaviour stripped the package prefix, which (a) let {@code PKG1.MYPROC},
 * {@code PKG2.MYPROC} and the standalone {@code MYPROC} silently collide into one selectable
 * entry and (b) always ended in an empty-body skip, since {@code ALL_SOURCE} stores package
 * members under the package name.
 */
class PackageRoutineListingTest {

    @Test
    void packageMembersAreExcludedAndStandalonesKept() throws Exception {
        ResultSet procRows = mock(ResultSet.class);
        when(procRows.next()).thenReturn(true, true, true, false);
        when(procRows.getString("PROCEDURE_NAME"))
                .thenReturn("STANDALONE_PRC", "APP_PKG.DO_WORK", "APP_PKG.OTHER");
        when(procRows.getString("PROCEDURE_CAT")).thenReturn(null);
        when(procRows.getString("PROCEDURE_SCHEM")).thenReturn(null);

        ResultSet emptyFunctions = mock(ResultSet.class);
        when(emptyFunctions.next()).thenReturn(false);

        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getProcedures(any(), any(), any())).thenReturn(procRows);
        when(md.getFunctions(any(), any(), any())).thenReturn(emptyFunctions);

        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(md);

        GenericMetadataReader reader = new GenericMetadataReader();

        List<String> names = reader.listProcedureNames(conn, null);

        assertThat(names).containsExactly("STANDALONE_PRC");
    }

    @Test
    void sameShortNameInTwoPackagesDoesNotCollideWithTheStandalone() throws Exception {
        ResultSet procRows = mock(ResultSet.class);
        when(procRows.next()).thenReturn(true, true, false);
        when(procRows.getString("PROCEDURE_NAME"))
                .thenReturn("MYPROC", "PKG1.MYPROC");
        when(procRows.getString("PROCEDURE_CAT")).thenReturn(null);
        when(procRows.getString("PROCEDURE_SCHEM")).thenReturn(null);

        ResultSet emptyFunctions = mock(ResultSet.class);
        when(emptyFunctions.next()).thenReturn(false);

        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getProcedures(any(), any(), any())).thenReturn(procRows);
        when(md.getFunctions(any(), any(), any())).thenReturn(emptyFunctions);

        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(md);

        List<String> names = new GenericMetadataReader().listProcedureNames(conn, null);

        assertThat(names).containsExactly("MYPROC");
    }
}
