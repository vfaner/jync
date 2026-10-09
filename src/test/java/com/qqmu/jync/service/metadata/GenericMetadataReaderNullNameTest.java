package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ProcedureMeta;

/**
 * P2/P3: a driver that reports a getProcedureColumns row with PROCEDURE_NAME = null (allowed by
 * the JDBC spec) used to throw a NullPointerException inside stripPackagePrefix and abort the
 * read. The row must simply be skipped.
 */
class GenericMetadataReaderNullNameTest {

    @Test
    void aNullProcedureNameRowIsSkippedInsteadOfThrowing() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true).thenReturn(false);
        when(rs.getString("PROCEDURE_NAME")).thenReturn(null);

        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getProcedureColumns(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(rs);

        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(metaData);

        GenericMetadataReader reader = new GenericMetadataReader();

        ProcedureMeta proc = reader.readProcedure(conn, "dbo", "P1");

        assertThat(proc.getName()).isEqualTo("P1");
        assertThat(proc.getParameters()).isEmpty();
    }
}
