package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

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
    void theEmptyStringRescueCanBeSwitchedOffAndSurvivesAReversion() {
        // sync.lob.oracle-empty-string-as-empty-clob=false must actually restore the old
        // ''→NULL binding; a setting that is documented but ignored is worse than none.
        SqlDialect off = new OracleDialect().withEmptyStringAsLob(false);
        assertThat(off.bindsEmptyStringAsLob()).isFalse();
        // The per-target instance is rebuilt from the server version; the switch rides along
        // so a 19c→11g downgrade cannot silently re-enable the rescue.
        assertThat(off.withServerMajorVersion(11).bindsEmptyStringAsLob()).isFalse();
        assertThat(new OracleDialect().withEmptyStringAsLob(true).bindsEmptyStringAsLob())
                .isTrue();
    }

    @Test
    void onlyOracleNeedsAServerSideLobForAStreamedValue() {
        // Oracle's MERGE placeholder is sent as a LONG; a stream bound directly would hit
        // the same 32767-byte ceiling. Copying into a temporary CLOB is the only way.
        assertThat(new OracleDialect().requiresServerSideLob()).isTrue();
        assertThat(new MySqlDialect().requiresServerSideLob()).isFalse();
        assertThat(new PostgresDialect().requiresServerSideLob()).isFalse();
        assertThat(new SqlServerDialect().requiresServerSideLob()).isFalse();
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
        assertThat(dialect.bindJdbcType(column(Types.VARCHAR, 200), null, Types.VARCHAR))
                .isEqualTo(Types.VARCHAR);
        assertThat(dialect.bindJdbcType(column(Types.INTEGER), null, Types.INTEGER))
                .isEqualTo(Types.INTEGER);
    }

    /**
     * 源列本身不是 LOB，但它的宽度会让 Oracle 目标列变成 LOB：{@code varcharType} 把
     * 超过 1000 字符的字符串建成 CLOB，{@code binaryType} 把超过 2000 字节的二进制造
     * 成 BLOB。这类列的值仍是普通 String/byte[]，绑定类型若不跟着目标走，超过 32767
     * 字节就不会走临时 CLOB，MERGE 的 LONG 占位符照样 ORA-01461；空串也不会得到
     * ''→空 CLOB 的救援。
     */
    @Test
    void wideSourceVarcharIsBoundAsClobBecauseItsTargetColumnIsAClob() {
        OracleDialect dialect = new OracleDialect();
        ColumnMeta wide = column(Types.VARCHAR, 12_000);
        wide.setTypeName("VARCHAR");
        assertThat(dialect.mapType(wide, DatabaseType.MYSQL)).isEqualTo("CLOB");
        assertThat(dialect.bindJdbcType(wide, DatabaseType.MYSQL, Types.VARCHAR))
                .isEqualTo(Types.CLOB);
    }

    @Test
    void wideSourceVarbinaryIsBoundAsBlobBecauseItsTargetColumnIsABlob() {
        OracleDialect dialect = new OracleDialect();
        ColumnMeta wide = column(Types.VARBINARY, 5_000);
        wide.setTypeName("VARBINARY");
        assertThat(dialect.mapType(wide, DatabaseType.MYSQL)).isEqualTo("BLOB");
        assertThat(dialect.bindJdbcType(wide, DatabaseType.MYSQL, Types.VARBINARY))
                .isEqualTo(Types.BLOB);
    }

    /** A width that still fits VARCHAR2 keeps its ordinary bind type. */
    @Test
    void narrowSourceVarcharKeepsItsOrdinaryBindType() {
        OracleDialect dialect = new OracleDialect();
        ColumnMeta narrow = column(Types.VARCHAR, 200);
        narrow.setTypeName("VARCHAR");
        assertThat(dialect.mapType(narrow, DatabaseType.MYSQL)).isEqualTo("VARCHAR2(200 CHAR)");
        assertThat(dialect.bindJdbcType(narrow, DatabaseType.MYSQL, Types.VARCHAR))
                .isEqualTo(Types.VARCHAR);
    }

    /**
     * 宽度未知时 Oracle 的 {@code varcharType(0)} 同样建 CLOB（注释写明 unknown/zero
     * sizes 落到 CLOB），绑定类型必须跟着走：否则目标列是 CLOB、占位符却是 VARCHAR，
     * 超过 32767 字节照样 ORA-01461，空串也拿不到空 CLOB 救援。
     */
    @Test
    void unknownWidthIsBoundAsClobBecauseOracleCreatesAClob() {
        OracleDialect dialect = new OracleDialect();
        ColumnMeta unknown = column(Types.VARCHAR, null);
        unknown.setTypeName("VARCHAR");
        assertThat(dialect.mapType(unknown, DatabaseType.MYSQL)).isEqualTo("CLOB");
        assertThat(dialect.bindJdbcType(unknown, DatabaseType.MYSQL, Types.VARCHAR))
                .isEqualTo(Types.CLOB);
    }

    /** A CHAR wider than 500 chars is created as VARCHAR2/CLOB; at 1200 it is a CLOB. */
    @Test
    void wideCharIsBoundAsClob() {
        OracleDialect dialect = new OracleDialect();
        ColumnMeta wide = column(Types.CHAR, 1_200);
        wide.setTypeName("CHAR");
        assertThat(dialect.mapType(wide, DatabaseType.MYSQL)).isEqualTo("CLOB");
        assertThat(dialect.bindJdbcType(wide, DatabaseType.MYSQL, Types.CHAR))
                .isEqualTo(Types.CLOB);
    }

    /** A width that still fits RAW keeps its ordinary bind type. */
    @Test
    void narrowVarbinaryKeepsItsOrdinaryBindType() {
        OracleDialect dialect = new OracleDialect();
        ColumnMeta narrow = column(Types.VARBINARY, 800);
        narrow.setTypeName("VARBINARY");
        assertThat(dialect.mapType(narrow, DatabaseType.MYSQL)).isEqualTo("RAW(800)");
        assertThat(dialect.bindJdbcType(narrow, DatabaseType.MYSQL, Types.VARBINARY))
                .isEqualTo(Types.VARBINARY);
    }

    private static ColumnMeta column(int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setJdbcType(jdbcType);
        return column;
    }

    private static ColumnMeta column(int jdbcType, Integer size) {
        ColumnMeta column = column(jdbcType);
        column.setSize(size);
        return column;
    }
}
