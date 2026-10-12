package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;

import org.junit.jupiter.api.Test;

class OracleLiteralConvertersTest {

    @Test
    void nullLiteral() {
        assertThat(OracleLiteralConverters.convert("NULL", Types.VARCHAR)).isNull();
    }

    @Test
    void quotedStringWithEscape() {
        assertThat(OracleLiteralConverters.convert("'it''s'", Types.VARCHAR)).isEqualTo("it's");
    }

    @Test
    void nationalCharString() {
        assertThat(OracleLiteralConverters.convert("N'中文'", Types.NVARCHAR)).isEqualTo("中文");
    }

    @Test
    void numberAsStringForNumericColumn() {
        // LogMiner ships NUMBER values as quoted strings; for a BIGINT column we keep the
        // text verbatim (JdbcRowMapper.bind coerces via setObject(sqlType, string)).
        assertThat(OracleLiteralConverters.convert("'1'", Types.BIGINT)).isEqualTo("1");
        assertThat(OracleLiteralConverters.convert("'-1.5'", Types.DECIMAL)).isEqualTo("-1.5");
    }

    @Test
    void bareNumberForNumericColumn() {
        assertThat(OracleLiteralConverters.convert("42", Types.INTEGER)).isEqualTo("42");
        assertThat(OracleLiteralConverters.convert("1.2E3", Types.DOUBLE)).isEqualTo("1.2E3");
    }

    @Test
    void toDateInnerStringSurfacedVerbatim() {
        // NLS-dependent date text is surfaced as-is; the target JDBC driver interprets it.
        Object v = OracleLiteralConverters.convert(
                "TO_DATE('2026-10-12','YYYY-MM-DD')", Types.DATE);
        assertThat(v).isEqualTo("2026-10-12");
    }

    @Test
    void toTimestampInnerStringSurfacedVerbatim() {
        // The 23ai fixture's NLS-shaped timestamp: TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')
        Object v = OracleLiteralConverters.convert(
                "TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')", Types.TIMESTAMP);
        assertThat(v).isEqualTo("12-10月-26 04.29.23.261316 上午");
    }

    @Test
    void toTimestampTzInnerString() {
        Object v = OracleLiteralConverters.convert(
                "TO_TIMESTAMP_TZ('12-10月-26 04.29.23.261316 上午 +08:00','...')",
                Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(v).isEqualTo("12-10月-26 04.29.23.261316 上午 +08:00");
    }

    @Test
    void hextorawToBytes() {
        Object v = OracleLiteralConverters.convert("HEXTORAW('DEADBEEF')", Types.VARBINARY);
        assertThat(v).isEqualTo(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
    }

    @Test
    void emptyClobAndBlob() {
        assertThat(OracleLiteralConverters.convert("EMPTY_CLOB()", Types.CLOB)).isEqualTo("");
        assertThat(OracleLiteralConverters.convert("EMPTY_BLOB()", Types.BLOB))
                .isEqualTo(new byte[0]);
    }

    @Test
    void rowidInValuePositionRejected() {
        assertThatThrownBy(() -> OracleLiteralConverters.convert("ROWID", Types.VARCHAR))
                .isInstanceOf(OracleLiteralConverters.UnsupportedLiteralException.class);
    }

    @Test
    void unsupportedLiteralThrows() {
        assertThatThrownBy(() -> OracleLiteralConverters.convert("SOME_WEIRD_FUNC(1)", Types.VARCHAR))
                .isInstanceOf(OracleLiteralConverters.UnsupportedLiteralException.class);
    }

    @Test
    void unsupportedMessageTruncatesLongLiteral() {
        String longLiteral = "X".repeat(200);
        try {
            OracleLiteralConverters.convert(longLiteral, Types.VARCHAR);
        } catch (OracleLiteralConverters.UnsupportedLiteralException e) {
            assertThat(e.getMessage()).hasSizeLessThanOrEqualTo(90);
        }
    }
}
