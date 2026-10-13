package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PgTestDecodingParserTest {

    private final PgTestDecodingParser parser = new PgTestDecodingParser();

    @Test
    void beginAndCommitLinesParseAsTransactionBoundaries() {
        PgTestDecodingParser.SlotLine start = parser.parse("BEGIN 12345");
        assertThat(start.txStart).isTrue();
        assertThat(start.txEnd).isFalse();
        assertThat(start.xid).isEqualTo(12345L);

        PgTestDecodingParser.SlotLine commit = parser.parse("COMMIT 12345");
        assertThat(commit.txStart).isFalse();
        assertThat(commit.txEnd).isTrue();
        assertThat(commit.xid).isEqualTo(12345L);
    }

    @Test
    void insertRowParsesColumnAndValuePairs() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.orders: INSERT: id[integer]:1 status[text]:'new' total[numeric]:9.99");
        assertThat(line.txStart).isFalse();
        assertThat(line.txEnd).isFalse();
        assertThat(line.change.op).isEqualTo(PgTestDecodingParser.Op.INSERT);
        assertThat(line.change.qualifiedTable).isEqualTo("public.orders");
        assertThat(line.change.row.get("id")).isEqualTo(1);
        assertThat(line.change.row.get("status")).isEqualTo("new");
        assertThat(line.change.row.get("total")).isEqualTo(new java.math.BigDecimal("9.99"));
    }

    @Test
    void updateRowSkipsOldImageAndKeepsNewImage() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.orders: UPDATE: old key-id[integer]:1 : new id[integer]:1"
                        + " status[text]:'paid' total[numeric]:19.98");
        assertThat(line.change.op).isEqualTo(PgTestDecodingParser.Op.UPDATE);
        assertThat(line.change.row.get("status")).isEqualTo("paid");
        assertThat(line.change.row.get("total")).isEqualTo(new java.math.BigDecimal("19.98"));
    }

    @Test
    void deleteRowParsesKeyImage() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.orders: DELETE: id[integer]:42");
        assertThat(line.change.op).isEqualTo(PgTestDecodingParser.Op.DELETE);
        assertThat(line.change.row.get("id")).isEqualTo(42);
    }

    @Test
    void nullColumnParsesAsJavaNull() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: id[integer]:1 note[text]:NULL");
        assertThat(line.change.row.get("note")).isNull();
        assertThat(line.change.row.get("id")).isEqualTo(1);
    }

    @Test
    void quotedStringWithDoubledQuoteUnescapes() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: id[integer]:1 body[text]:'it''s a test'");
        assertThat(line.change.row.get("body")).isEqualTo("it's a test");
        assertThat(line.change.row.get("id")).isEqualTo(1);
    }

    @Test
    void nonTableLineIsSkippedWithReason() {
        PgTestDecodingParser.SlotLine line = parser.parse("some random noise");
        assertThat(line.change).isNotNull();
        assertThat(line.change.isSkipped()).isTrue();
        assertThat(line.change.unsupportedReason).contains("not a row event");
    }

    // —— P1-4:numeric/money 精度与 timestamptz/timetz 时区偏移。numeric 走 Double
    // 会把 38 位精度砍到 15-17 位(源 numeric(38,10) → 目标精度对不上);timestamptz
    // 输出带 "+08" 后缀,Timestamp.valueOf 直接抛异常回退成字符串 → 目标端绑定失败。——

    @Test
    void numericKeepsBigDecimalPrecision() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: id[integer]:1"
                        + " total[numeric]:12345678901234567890.1234567890");
        Object total = line.change.row.get("total");
        assertThat(total).isInstanceOf(java.math.BigDecimal.class);
        assertThat((java.math.BigDecimal) total)
                .isEqualByComparingTo("12345678901234567890.1234567890");
    }

    @Test
    void moneyStripsLocaleDecorationsIntoBigDecimal() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: a[money]:'$9.99' b[money]:'-$1,234.56'"
                        + " c[money]:'($0.50)'");
        assertThat(line.change.row.get("a")).isInstanceOf(java.math.BigDecimal.class);
        assertThat((java.math.BigDecimal) line.change.row.get("a"))
                .isEqualByComparingTo("9.99");
        assertThat((java.math.BigDecimal) line.change.row.get("b"))
                .isEqualByComparingTo("-1234.56");
        // 括号是 money 的负数写法(部分 lc_monetary)。
        assertThat((java.math.BigDecimal) line.change.row.get("c"))
                .isEqualByComparingTo("-0.50");
    }

    @Test
    void timestamptzParsesInstantWithUtcOffset() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: a[timestamptz]:'2026-10-13 10:00:00+08'"
                        + " b[timestamp with time zone]:'2026-10-13 10:00:00.123456+05:30'");
        // 瞬时值语义,与基线通路(rs.getTimestamp)一致:10:00+08 == 02:00Z。
        assertThat(line.change.row.get("a")).isEqualTo(java.sql.Timestamp.from(
                java.time.LocalDateTime.parse("2026-10-13T10:00:00")
                        .toInstant(java.time.ZoneOffset.ofHours(8))));
        assertThat(line.change.row.get("b")).isEqualTo(java.sql.Timestamp.from(
                java.time.LocalDateTime.parse("2026-10-13T10:00:00.123456")
                        .toInstant(java.time.ZoneOffset.ofHoursMinutes(5, 30))));
    }

    @Test
    void timestamptzWithoutOffsetFallsBackToWallTime() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: a[timestamptz]:'2026-10-13 10:00:00'");
        assertThat(line.change.row.get("a"))
                .isEqualTo(java.sql.Timestamp.valueOf("2026-10-13 10:00:00"));
    }

    @Test
    void timetzStripsUtcOffset() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: a[timetz]:'10:00:00+08'");
        assertThat(line.change.row.get("a"))
                .isEqualTo(java.sql.Time.valueOf("10:00:00"));
    }

    // —— P2-1:bytea \x hex → byte[];boolean 大小写不敏感 ——

    @Test
    void byteaHexLiteralDecodesToByteArray() {
        // test_decoding 默认 bytea_output=hex 输出 \x48656c6c6f;保持字符串会让
        // 目标端 bytea 列绑定失败(跨库目标尤其)。必须解码成 byte[]。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: id[integer]:1 data[bytea]:\\x48656c6c6f");
        assertThat(line.change.row.get("data")).isEqualTo(new byte[]{
                (byte) 0x48, (byte) 0x65, (byte) 0x6c, (byte) 0x6c, (byte) 0x6f});
    }

    @Test
    void byteaWithoutHexPrefixFallsBackToRawString() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: data[bytea]:oddformat");
        assertThat(line.change.row.get("data")).isEqualTo("oddformat");
    }

    @Test
    void booleanLiteralsAreCaseInsensitive() {
        // 旧实现 raw.equals("t") 大小写敏感:'T' 会静默变 false(数据损坏)。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: a[bool]:T b[boolean]:TRUE c[bool]:f"
                        + " d[boolean]:false e[bool]:t");
        assertThat(line.change.row.get("a")).isEqualTo(true);
        assertThat(line.change.row.get("b")).isEqualTo(true);
        assertThat(line.change.row.get("c")).isEqualTo(false);
        assertThat(line.change.row.get("d")).isEqualTo(false);
        assertThat(line.change.row.get("e")).isEqualTo(true);
    }

    // —— P2-2:UPDATE " new " 分隔符必须是 quote-aware 状态机 ——

    @Test
    void updateOldValueContainingSpaceNewSpaceDoesNotCorruptNewImage() {
        // old key 的 text 值恰含 " new "(如 'buy new car')时,裸 indexOf(" new ")
        // 会命中引号内文本 → new image 被拦腰截断、目标端收到损坏行。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.orders: UPDATE: old key-username[text]:'buy new car'"
                        + " : new username[text]:'bob' status[text]:'paid'");
        assertThat(line.change.isSkipped()).isFalse();
        assertThat(line.change.row.get("username")).isEqualTo("bob");
        assertThat(line.change.row.get("status")).isEqualTo("paid");
    }

    @Test
    void updateOldValueContainingColonNewPatternParsesNewImage() {
        // 更强的对抗形态:引号值内含完整的 " : new " 序列。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: UPDATE: old key-note[text]:'see : new doc'"
                        + " : new note[text]:'x'");
        assertThat(line.change.row.get("note")).isEqualTo("x");
    }

    @Test
    void bareValueNewBeforeRealSeparatorIsNotTheMarker() {
        // enum/裸词值恰好是 new:列值的冒号紧贴 ']'(前面无空白),不构成分隔符
        // 形态「空白 + ':' + 空白 + new」;状态机不得误认。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: UPDATE: old key-mood[public.mood]:new"
                        + " : new mood[public.mood]:happy");
        assertThat(line.change.row.get("mood")).isEqualTo("happy");
    }
}
