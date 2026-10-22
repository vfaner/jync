package com.qqmu.jync.service.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.converter.SqlDialect;

/**
 * MySQL DATETIME reaches the binder as the server's wall-clock TEXT — Connector/J's
 * {@code getTimestamp} shifts the value across zones, so {@link JdbcRowMapper#readRow}
 * keeps the literal (see the DATETIME branch there). PostgreSQL-family targets absorb
 * that text through their placeholder's {@code ?::timestamp} cast and MySQL/T-SQL/DB2
 * convert it implicitly, but Oracle does not: its VARCHAR2 → DATE/TIMESTAMP conversion
 * runs through the session's NLS format, which rejects the ISO-ish literal outright.
 *
 * <p>docker 实测 MySQL 8 → Oracle 23ai:基线全量在
 * {@code ORA-01843: 指定的月份无效} 上整批失败,整个方向无法建立。
 */
class JdbcRowMapperTemporalTextBindTest {

    private final PreparedStatement ps = mock(PreparedStatement.class);

    /** The exact shape Connector/J's getString produces for a DATETIME(6) cell. */
    private static final String DATETIME_TEXT = "2026-10-13 12:34:56.789012";

    @Test
    void mysqlWallClockTextBecomesATimestampForAnOracleTarget() throws Exception {
        bind(ps, DATETIME_TEXT, Types.TIMESTAMP, new OracleDialect(23));

        verify(ps).setTimestamp(1, Timestamp.valueOf(DATETIME_TEXT));
    }

    @Test
    void mysqlWallClockTextStaysVerbatimForAMysqlTarget() throws Exception {
        // MySQL 目标靠隐式转换按字面存下原墙上时间;换成 Timestamp 会被驱动按会话时区
        // 再折算一次(CdcVolumeAndTypesIT.datetimeRoundTrip 守的就是这个字面值)。
        bind(ps, DATETIME_TEXT, Types.TIMESTAMP, new MySqlDialect());

        verify(ps).setObject(1, DATETIME_TEXT);
    }

    @Test
    void mysqlWallClockTextStaysVerbatimForAPostgresTarget() throws Exception {
        // PG 家族的占位符自己带 ?::timestamp 转换,值保持文本。
        bind(ps, DATETIME_TEXT, Types.TIMESTAMP, new PostgresDialect());

        verify(ps).setObject(1, DATETIME_TEXT);
    }

    /**
     * TIME 列:Xync 读取路径对所有源端的 TIME 都取服务器字面量文本("12:34:56"),而 Oracle
     * 目标端没有 TIME 类型——结构同步把 TIME 建成了 TIMESTAMP。文本一旦原样绑定,
     * Oracle 会用 NLS_TIMESTAMP_FORMAT 去解析只带时分秒的字面量,整批行报
     * ORA-01861 "literal does not match format string"(与 DATETIME 的 ORA-01843 同源)。
     * 解析成 Timestamp 时必须补一个日期锚点;1970-01-01 是驱动把 java.sql.Time 映射到
     * Oracle DATE 时用的同一个锚点,因此与原生时态源端的行为一致。
     */
    @Test
    void timeOfDayTextBecomesAnAnchoredTimestampForAnOracleTarget() throws Exception {
        bind(ps, "12:34:56", Types.TIME, new OracleDialect(23));

        verify(ps).setTimestamp(1, Timestamp.valueOf("1970-01-01 12:34:56"));
    }

    @Test
    void timeOfDayTextKeepsItsFractionalSeconds() throws Exception {
        bind(ps, "12:34:56.789", Types.TIME, new OracleDialect(23));

        verify(ps).setTimestamp(1, Timestamp.valueOf("1970-01-01 12:34:56.789"));
    }

    /** MySQL 的扩展 TIME 域(小时数 ≥ 24、负数)没有任何时间类型能承载,保持原样绑定。 */
    @Test
    void extendedMysqlTimeStaysVerbatimOnOracle() throws Exception {
        bind(ps, "838:59:59", Types.TIME, new OracleDialect(23));

        verify(ps).setObject(1, "838:59:59");
    }

    @Test
    void textNoTemporalCanHoldStillBindsVerbatimOnOracle() throws Exception {
        // MySQL TIME 的域(-838:59:59)超出任何 java.sql 时间类型;解析不了就原样绑定,
        // 新的转换分支不许因此抛异常。
        bind(ps, "-838:59:59", Types.TIMESTAMP, new OracleDialect(23));

        verify(ps).setObject(1, "-838:59:59");
    }

    @Test
    void textThatMerelyLooksLikeATimestampIsNotRewrittenForAnOracleTarget() throws Exception {
        // 目标列是 VARCHAR2(源端 MySQL 的 VARCHAR 列,结构同步就这么建):值必须原样过去。
        // 只看"文本 + Oracle 目标"就转 Timestamp 的话,Oracle 会按 NLS_TIMESTAMP_FORMAT
        // 把它重新渲染成 "13-10月-26 12.34.56.000000 下午" —— 一次本来无害的文本同步
        // 被悄悄改写。转换只在真正的时态列上发生。
        bind(ps, "2026-10-13 12:34:56", Types.VARCHAR, new OracleDialect(23));

        verify(ps).setObject(1, "2026-10-13 12:34:56");
        verify(ps, never()).setTimestamp(anyInt(), any());
    }

    @Test
    void anUnknownTargetKeepsTheText() throws Exception {
        // 没有目标方言的四参调用(老调用点/测试)行为不变。
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c", DATETIME_TEXT);
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("c", Types.TIMESTAMP);

        JdbcRowMapper.bind(ps, row, List.of("c"), types);

        verify(ps).setObject(1, DATETIME_TEXT);
    }

    private static void bind(PreparedStatement ps, Object value, int targetJdbcType,
                             SqlDialect targetDialect) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c", value);
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("c", targetJdbcType);
        JdbcRowMapper.bind(ps, row, List.of("c"), types, targetDialect);
    }
}
