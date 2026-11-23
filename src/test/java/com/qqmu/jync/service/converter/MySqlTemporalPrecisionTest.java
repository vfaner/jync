package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * MySQL → MySQL must keep the source's fractional-second precision. Widening every temporal
 * to (6) makes the target schema differ from the source and leaves structure sync trying to
 * "repair" a column that was correct all along.
 */
class MySqlTemporalPrecisionTest {

    private final MySqlDialect dialect = new MySqlDialect();

    private static ColumnMeta column(String typeName, int digits) {
        ColumnMeta column = new ColumnMeta();
        column.setName("updated_at");
        column.setTypeName(typeName);
        column.setJdbcType(Types.TIMESTAMP);
        column.setDecimalDigits(digits);
        return column;
    }

    @Test
    void sameFamilyDatetimeKeepsItsPrecision() {
        assertThat(dialect.mapType(column("DATETIME", 0), DatabaseType.MYSQL))
                .isEqualTo("DATETIME");
        assertThat(dialect.mapType(column("DATETIME", 3), DatabaseType.MYSQL))
                .isEqualTo("DATETIME(3)");
        assertThat(dialect.mapType(column("DATETIME", 6), DatabaseType.MYSQL))
                .isEqualTo("DATETIME(6)");
        assertThat(dialect.mapType(column("TIMESTAMP", 0), DatabaseType.MYSQL))
                .isEqualTo("DATETIME");
    }

    @Test
    void sameFamilyTimeKeepsItsPrecision() {
        ColumnMeta time = column("TIME", 0);
        time.setJdbcType(Types.TIME);
        assertThat(dialect.mapType(time, DatabaseType.MYSQL)).isEqualTo("TIME");

        time.setDecimalDigits(6);
        assertThat(dialect.mapType(time, DatabaseType.MYSQL)).isEqualTo("TIME(6)");
    }

    @Test
    void sameFamilyTextAndBlobTypesKeepTheirCapacity() {
        ColumnMeta text = column("TEXT", 0);
        text.setJdbcType(Types.LONGVARCHAR);
        text.setSize(65535);
        assertThat(dialect.mapType(text, DatabaseType.MYSQL)).isEqualTo("TEXT");

        text.setTypeName("LONGTEXT");
        assertThat(dialect.mapType(text, DatabaseType.MYSQL)).isEqualTo("LONGTEXT");

        ColumnMeta blob = column("BLOB", 0);
        blob.setJdbcType(Types.LONGVARBINARY);
        blob.setSize(65535);
        assertThat(dialect.mapType(blob, DatabaseType.MYSQL)).isEqualTo("BLOB");

        blob.setTypeName("MEDIUMBLOB");
        assertThat(dialect.mapType(blob, DatabaseType.MYSQL)).isEqualTo("MEDIUMBLOB");
    }

    @Test
    void aFixedBinaryColumnStaysFixedLength() {
        ColumnMeta binary = column("BINARY", 0);
        binary.setJdbcType(Types.BINARY);
        binary.setSize(16);
        assertThat(dialect.mapType(binary, DatabaseType.MYSQL)).isEqualTo("BINARY(16)");
    }

    @Test
    void aForeignTimestampStillWidensToSixDigits() {
        // PostgreSQL/SQL Server/Oracle sources can carry microseconds; the target must not
        // truncate them, so the old DATETIME(6) default stays for cross-family targets.
        assertThat(dialect.mapType(column("TIMESTAMP", 6), DatabaseType.POSTGRESQL))
                .isEqualTo("DATETIME(6)");
    }

    @Test
    void aPlainDateTimeColumnIsCreatedWithoutFractionalPrecision() {
        ColumnMeta c = column("DATETIME", 0);
        c.setDefaultValue("CURRENT_TIMESTAMP");
        TableMeta table = new TableMeta();
        table.setName("orders");
        table.setColumns(List.of(c));

        assertThat(dialect.getCreateTableSql(table, "cee", "orders", DatabaseType.MYSQL))
                .contains("`updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP")
                .doesNotContain("DATETIME(6)");
    }

    @Test
    void theCurrentTimestampDefaultMatchesTheColumnPrecision() {
        assertThat(dialect.adjustDefaultForType("CURRENT_TIMESTAMP(6)", "DATETIME"))
                .isEqualTo("CURRENT_TIMESTAMP");
        assertThat(dialect.adjustDefaultForType("CURRENT_TIMESTAMP(6)", "DATETIME(3)"))
                .isEqualTo("CURRENT_TIMESTAMP(3)");
        assertThat(dialect.adjustDefaultForType("CURRENT_TIMESTAMP(6)", "DATETIME(6)"))
                .isEqualTo("CURRENT_TIMESTAMP(6)");
    }
}
