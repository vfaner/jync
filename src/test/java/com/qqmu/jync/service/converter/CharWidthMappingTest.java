package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * 定长 CHAR 的宽度不能被目标端的 CHAR 上限悄悄截短。
 *
 * <p>旧实现一律 {@code Math.min(size, 平台上限)}：SQL Server 的 {@code NCHAR(5000)} 到
 * Oracle 变成 {@code CHAR(500)}，MySQL 的 {@code CHAR(255)} 到 DB2 变成 DB2 根本不接受的
 * {@code CHAR(255)}（DB2 上限 254）。前者在写入超长值时以 ORA-12899 失败，后者连建表都
 * 过不去——两者都是与同步配置无关的后期失败。超过上限时应提升为 VARCHAR/LOB，保住数据
 * 而不是保住“定长”这个声明。
 */
class CharWidthMappingTest {

    private static ColumnMeta charColumn(int size) {
        ColumnMeta column = new ColumnMeta();
        column.setName("c");
        column.setJdbcType(Types.CHAR);
        column.setTypeName("CHAR");
        column.setSize(size);
        return column;
    }

    @Test
    void db2CharCeilingIs254SoA255WideSourceWidensToVarchar() {
        assertThat(new Db2Dialect().mapType(charColumn(255), DatabaseType.MYSQL))
                .isEqualTo("VARCHAR(255)");
    }

    @Test
    void oracleBeyondItsCharCeilingKeepsTheContentAsVarchar() {
        assertThat(new OracleDialect().mapType(charColumn(800), DatabaseType.POSTGRESQL))
                .isEqualTo("VARCHAR2(800 CHAR)");
    }

    @Test
    void oracleBeyondVarchar2CeilingBecomesClob() {
        assertThat(new OracleDialect().mapType(charColumn(2000), DatabaseType.POSTGRESQL))
                .isEqualTo("CLOB");
    }

    @Test
    void sqlServerBeyondNchar4000PromotesToNvarcharMax() {
        assertThat(new SqlServerDialect().mapType(charColumn(5000), DatabaseType.POSTGRESQL))
                .isEqualTo("NVARCHAR(MAX)");
    }

    @Test
    void postgresKeepsAWidthItsOwnCharAllows() {
        assertThat(new PostgresDialect().mapType(charColumn(300), DatabaseType.POSTGRESQL))
                .isEqualTo("CHAR(300)");
    }

    @Test
    void widthsWithinTheCeilingStayFixedWidth() {
        assertThat(new MySqlDialect().mapType(charColumn(100), DatabaseType.POSTGRESQL))
                .isEqualTo("CHAR(100)");
        assertThat(new OracleDialect().mapType(charColumn(100), DatabaseType.MYSQL))
                .isEqualTo("CHAR(100 CHAR)");
        assertThat(new SqlServerDialect().mapType(charColumn(100), DatabaseType.MYSQL))
                .isEqualTo("NCHAR(100)");
    }
}
