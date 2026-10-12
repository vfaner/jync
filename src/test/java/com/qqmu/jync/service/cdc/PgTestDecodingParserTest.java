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
        assertThat(line.change.row.get("id")).isEqualTo("1");
        assertThat(line.change.row.get("status")).isEqualTo("new");
        assertThat(line.change.row.get("total")).isEqualTo("9.99");
    }

    @Test
    void updateRowSkipsOldImageAndKeepsNewImage() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.orders: UPDATE: old key-id[integer]:1 : new id[integer]:1"
                        + " status[text]:'paid' total[numeric]:19.98");
        assertThat(line.change.op).isEqualTo(PgTestDecodingParser.Op.UPDATE);
        assertThat(line.change.row.get("status")).isEqualTo("paid");
        assertThat(line.change.row.get("total")).isEqualTo("19.98");
    }

    @Test
    void deleteRowParsesKeyImage() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.orders: DELETE: id[integer]:42");
        assertThat(line.change.op).isEqualTo(PgTestDecodingParser.Op.DELETE);
        assertThat(line.change.row.get("id")).isEqualTo("42");
    }

    @Test
    void nullColumnParsesAsJavaNull() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: id[integer]:1 note[text]:NULL");
        assertThat(line.change.row.get("note")).isNull();
        assertThat(line.change.row.get("id")).isEqualTo("1");
    }

    @Test
    void quotedStringWithDoubledQuoteUnescapes() {
        PgTestDecodingParser.SlotLine line = parser.parse(
                "table public.t: INSERT: id[integer]:1 body[text]:'it''s a test'");
        assertThat(line.change.row.get("body")).isEqualTo("it's a test");
    }

    @Test
    void nonTableLineIsSkippedWithReason() {
        PgTestDecodingParser.SlotLine line = parser.parse("some random noise");
        assertThat(line.change).isNotNull();
        assertThat(line.change.isSkipped()).isTrue();
        assertThat(line.change.unsupportedReason).contains("not a row event");
    }
}
