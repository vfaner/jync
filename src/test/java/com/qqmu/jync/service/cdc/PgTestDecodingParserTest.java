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
    void updateRowSkipsOldKeyAndKeepsNewTuple() {
        // docker 实测(postgres:16,REPLICA IDENTITY FULL):真实的分隔标记是
        // "old-key:" … "new-tuple:",旧实现找的 " : new " 是 PostgreSQL 从不输出的格式。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.og_full: UPDATE: old-key: id[bigint]:1 name[text]:'a'"
                        + " new-tuple: id[bigint]:1 name[text]:'b'");
        assertThat(line.change.op).isEqualTo(PgTestDecodingParser.Op.UPDATE);
        assertThat(line.change.row).containsOnlyKeys("id", "name");
        assertThat(line.change.row.get("id")).isEqualTo(1L);
        assertThat(line.change.row.get("name")).isEqualTo("b");
    }

    @Test
    void updateWhoseKeyChangedKeepsTheNewKey() {
        // 主键变更时的真实输出(docker 实测):旧实现把 "old-key: id" 当作列名,
        // 新行里没有真正的主键列 → 目标端 upsert 绑 NULL 主键,重放永远失败直到 strike-out。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.og_pk: UPDATE: old-key: id[bigint]:1"
                        + " new-tuple: id[bigint]:2 name[text]:'a'");
        assertThat(line.change.row).containsOnlyKeys("id", "name");
        assertThat(line.change.row.get("id")).isEqualTo(2L);
        assertThat(line.change.row.get("name")).isEqualTo("a");
    }

    @Test
    void updateWithoutOldKeyParsesTheNewImageDirectly() {
        // 默认 REPLICA IDENTITY 且主键未变:没有 old-key/new-tuple 段,整行即新镜像
        // (docker 实测的 IT 流就是这个形态)。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.orders: UPDATE: id[integer]:7 status[text]:'changed'");
        assertThat(line.change.isSkipped()).isFalse();
        assertThat(line.change.row.get("id")).isEqualTo(7);
        assertThat(line.change.row.get("status")).isEqualTo("changed");
    }

    @Test
    void deleteRowParsesKeyImage() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.orders: DELETE: id[integer]:42");
        assertThat(line.change.op).isEqualTo(PgTestDecodingParser.Op.DELETE);
        assertThat(line.change.row.get("id")).isEqualTo(42);
    }

    @Test
    void nullTokenIsCaseInsensitiveAndParsesAsJavaNull() {
        // docker 实测(postgres:16):test_decoding 把 SQL NULL 打成小写 null
        // (test_decoding.c: appendStringInfoString(s, "null"))。旧实现只认大写 "NULL":
        // text 列被写成字符串 "null"(静默损坏),int/numeric 列绑定失败、事务重放
        // 直到 APPLY_STRIKE_OUT。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.og_nulls: INSERT: id[bigint]:1 note[text]:null n[integer]:null"
                        + " legacy[text]:NULL");
        assertThat(line.change.row.get("note")).isNull();
        assertThat(line.change.row.get("n")).isNull();
        assertThat(line.change.row.get("legacy")).isNull();
        assertThat(line.change.row.get("id")).isEqualTo(1L);
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

    // —— P2-2:"new-tuple:" 分隔符的扫描必须是 quote-aware 的 ——

    @Test
    void updateOldValueContainingTheNewTupleMarkerDoesNotCorruptNewImage() {
        // old-key 的 text 值恰含 "new-tuple:" 字样:标记扫描必须跳过引号内文本,
        // 否则 new image 被拦腰截断、目标端收到损坏行。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.orders: UPDATE: old-key: username[text]:'see new-tuple: x'"
                        + " new-tuple: username[text]:'bob' status[text]:'paid'");
        assertThat(line.change.isSkipped()).isFalse();
        assertThat(line.change.row).containsOnlyKeys("username", "status");
        assertThat(line.change.row.get("username")).isEqualTo("bob");
        assertThat(line.change.row.get("status")).isEqualTo("paid");
    }

    @Test
    void updateOldValueContainingQuotedColonMarkerParsesTheNewImage() {
        // 更强的对抗形态:引号值内含完整的 " : new-tuple: " 序列。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: UPDATE: old-key: note[text]:'see : new-tuple: doc'"
                        + " new-tuple: note[text]:'x'");
        assertThat(line.change.row).containsOnlyKeys("note");
        assertThat(line.change.row.get("note")).isEqualTo("x");
    }

    // —— test_decoding 的 TOAST 占位符:unchanged-toast-datum ——
    // 未修改的 TOASTed 值(test_decoding.c: VARATT_IS_EXTERNAL_ONDISK)只输出占位符,
    // 真实值不在流里。把它当普通字符串写进目标 = 静默损坏。

    @Test
    void unchangedToastDatumOnUpdateSkipsTheRowInsteadOfWritingTheSentinel() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: UPDATE: id[bigint]:1 body[text]:unchanged-toast-datum");
        assertThat(line.change.isSkipped()).isTrue();
        assertThat(line.change.unsupportedReason).contains("unchanged-toast-datum");
    }

    @Test
    void unchangedToastDatumOnInsertSkipsTheRowToo() {
        // INSERT ... SELECT 可以复用另一个元组的磁盘 TOAST 值,同样只输出占位符。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: id[bigint]:1 body[text]:unchanged-toast-datum");
        assertThat(line.change.isSkipped()).isTrue();
    }

    @Test
    void unchangedToastDatumOnDeleteDropsOnlyThatColumn() {
        // DELETE 的关键镜像在 REPLICA IDENTITY FULL 下是整行,但应用端只绑定主键列:
        // 丢掉无法还原的 TOAST 列,删除照常执行。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: DELETE: id[bigint]:1 body[text]:unchanged-toast-datum");
        assertThat(line.change.isSkipped()).isFalse();
        assertThat(line.change.row).containsOnlyKeys("id");
        assertThat(line.change.row.get("id")).isEqualTo(1L);
    }

    @Test
    void quotedLiteralThatLooksLikeTheToastSentinelIsKeptAsData() {
        // 引号值才是真正的文本数据——“unchanged-toast-datum” 作为列值必须原样保留。
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: id[bigint]:1 body[text]:'unchanged-toast-datum'");
        assertThat(line.change.isSkipped()).isFalse();
        assertThat(line.change.row.get("body")).isEqualTo("unchanged-toast-datum");
    }
}
