package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * P2: Oracle/VARCHAR2 defaults to BYTE semantics, which cannot hold the declared length in CJK
 * data. Generated string types must use CHAR semantics, or become a CLOB where that would
 * exceed the byte ceiling.
 */
class OracleDialectCharSemanticsTest {

    private final OracleDialect dialect = new OracleDialect();

    private ColumnMeta stringColumn(int size) {
        ColumnMeta column = new ColumnMeta();
        column.setName("S");
        column.setJdbcType(Types.VARCHAR);
        column.setSize(size);
        return column;
    }

    @Test
    void varcharUsesCharSemantics() {
        assertThat(dialect.mapType(stringColumn(100), DatabaseType.MYSQL))
                .isEqualTo("VARCHAR2(100 CHAR)");
    }

    @Test
    void aLengthThatCouldOverflowTheByteCeilingBecomesAClob() {
        // 2000 chars × up to 4 bytes exceeds 4000 bytes on a standard database.
        assertThat(dialect.mapType(stringColumn(2000), DatabaseType.MYSQL))
                .isEqualTo("CLOB");
    }

    @Test
    void anUnknownLengthBecomesAClob() {
        assertThat(dialect.mapType(stringColumn(0), DatabaseType.MYSQL))
                .isEqualTo("CLOB");
    }

    @Test
    void charAlsoUsesCharSemantics() {
        ColumnMeta column = new ColumnMeta();
        column.setName("C");
        column.setJdbcType(Types.CHAR);
        column.setSize(10);
        assertThat(dialect.mapType(column, DatabaseType.MYSQL))
                .isEqualTo("CHAR(10 CHAR)");
    }
}
