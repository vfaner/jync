package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * DB2's data dictionary stores unquoted identifiers in upper case, so every SYSCAT query
 * folds the name before binding it as a parameter. Folding with the default locale breaks
 * that on a Turkish JVM: "invoices" becomes "İNVOİCES", which matches no row.
 */
class Db2MetadataReaderLocaleTest {

    @Test
    void syscatParametersFoldIdentifiersWithTheRootLocale() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(5L);

        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(new Db2MetadataReader().estimateRowCount(conn, "inventory", "invoices"))
                    .isEqualTo(5L);
        } finally {
            Locale.setDefault(previous);
        }

        verify(ps).setString(1, "INVENTORY");
        verify(ps).setString(2, "INVOICES");
    }
}
