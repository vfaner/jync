package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;

/**
 * The bind-side type of a large value is not the source's JDBC type: a MySQL LONGTEXT
 * reports LONGVARCHAR but lands in a CLOB, and Oracle's MERGE needs the placeholder typed
 * as CLOB so a null carries the right type. Dialects without a bind ceiling keep the
 * effective type untouched.
 */
class SqlDialectLobBindTest {

    @Test
    void oracleDeclaresItsInlineBindCeiling() {
        assertThat(new OracleDialect().maxInlineBindBytes()).isEqualTo(32767);
    }

    @Test
    void otherDialectsHaveNoInlineCeiling() {
        assertThat(new MySqlDialect().maxInlineBindBytes()).isEqualTo(Integer.MAX_VALUE);
        assertThat(new PostgresDialect().maxInlineBindBytes()).isEqualTo(Integer.MAX_VALUE);
        assertThat(new SqlServerDialect().maxInlineBindBytes()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void onlyOracleBindsEmptyStringAsLob() {
        assertThat(new OracleDialect().bindsEmptyStringAsLob()).isTrue();
        assertThat(new MySqlDialect().bindsEmptyStringAsLob()).isFalse();
        assertThat(new PostgresDialect().bindsEmptyStringAsLob()).isFalse();
    }

    @Test
    void oracleMapsTextLobBindTypesToClob() {
        OracleDialect dialect = new OracleDialect();
        for (int type : new int[] {Types.LONGVARCHAR, Types.LONGNVARCHAR,
                Types.CLOB, Types.NCLOB}) {
            assertThat(dialect.bindJdbcType(null, null, type)).isEqualTo(Types.CLOB);
        }
    }

    @Test
    void oracleMapsBinaryLobBindTypesToBlob() {
        OracleDialect dialect = new OracleDialect();
        for (int type : new int[] {Types.LONGVARBINARY, Types.BLOB}) {
            assertThat(dialect.bindJdbcType(null, null, type)).isEqualTo(Types.BLOB);
        }
    }

    @Test
    void oracleLeavesOrdinaryTypesUnchanged() {
        OracleDialect dialect = new OracleDialect();
        assertThat(dialect.bindJdbcType(column(Types.VARCHAR), null, Types.VARCHAR))
                .isEqualTo(Types.VARCHAR);
        assertThat(dialect.bindJdbcType(column(Types.INTEGER), null, Types.INTEGER))
                .isEqualTo(Types.INTEGER);
    }

    private static ColumnMeta column(int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setJdbcType(jdbcType);
        return column;
    }
}
