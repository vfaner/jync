package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.OracleDialect;

/**
 * CDC replay binds through the same type map as the polling path, so it needs the same
 * canonicalization: a SQL Server geometry column's -157 would otherwise reach the Oracle
 * driver's setNull and fail with ORA-17004.
 */
class CdcTableBindingBindTypeTest {

    @Test
    void replayBindsUseTheCanonicalVendorType() {
        ColumnMeta geo = new ColumnMeta();
        geo.setName("GEO");
        geo.setJdbcType(-157);
        geo.setTypeName("geometry");
        TableMeta table = new TableMeta();
        table.setName("T");
        table.getColumns().add(geo);

        CdcTableBinding binding = CdcTableBinding.of("db", "t", table, "T", null);

        assertThat(binding.typeByColumn(new OracleDialect(), DatabaseType.SQLSERVER))
                .containsEntry("GEO", Types.BLOB);
    }
}
