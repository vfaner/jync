package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;

/**
 * The upsert's bind types come from the source result metadata. A vendor code the driver
 * reports (SQL Server geometry -157, sql_variant -156, datetimeoffset -155) reached
 * {@code PreparedStatement.setNull} unchanged, and ojdbc's
 * {@code OracleStatement.getInternalType} throws ORA-17004 for any code outside its
 * internal table — one NULL in such a column failed the table on every cycle.
 */
class DataSyncServiceBindTypeTest {

    private static final OracleDialect ORACLE = new OracleDialect();

    @Test
    void vendorCodesReachTheOracleDriverAsPortableTypes() {
        assertThat(DataSyncService.bindTypeFor(ORACLE, null, DatabaseType.SQLSERVER,
                -157, "geometry")).isEqualTo(Types.BLOB);
        assertThat(DataSyncService.bindTypeFor(ORACLE, null, DatabaseType.SQLSERVER,
                -156, "sql_variant")).isEqualTo(Types.CLOB);
        assertThat(DataSyncService.bindTypeFor(ORACLE, null, DatabaseType.SQLSERVER,
                -155, "datetimeoffset")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    }

    @Test
    void refinementsAndStandardTypesAreUntouched() {
        assertThat(DataSyncService.bindTypeFor(ORACLE, null, DatabaseType.MYSQL,
                Types.SMALLINT, "YEAR")).isEqualTo(Types.SMALLINT);
        assertThat(DataSyncService.bindTypeFor(new MySqlDialect(), null, DatabaseType.MYSQL,
                Types.VARCHAR, "VARCHAR")).isEqualTo(Types.VARCHAR);
    }
}
