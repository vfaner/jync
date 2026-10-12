package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

class SqlRedoParserTest {

    private final SqlRedoParser parser = new SqlRedoParser();

    /** Build a binding for SPIKE_T (ID BIGINT PK, NAME VARCHAR, NOTE VARCHAR, TS TIMESTAMP). */
    private static CdcTableBinding spikeBinding() {
        TableMeta meta = new TableMeta();
        meta.setName("SPIKE_T");
        meta.setSchema("SRC");
        meta.getColumns().add(col("ID", Types.BIGINT, true));
        meta.getColumns().add(col("NAME", Types.VARCHAR, false));
        meta.getColumns().add(col("NOTE", Types.VARCHAR, false));
        meta.getColumns().add(col("TS", Types.TIMESTAMP, false));
        meta.getPrimaryKeys().add("ID");
        return CdcTableBinding.of("SRC", "SPIKE_T", meta, "SPIKE_T", Collections.emptyMap());
    }

    private static ColumnMeta col(String name, int jdbcType, boolean pk) {
        ColumnMeta c = new ColumnMeta();
        c.setName(name);
        c.setJdbcType(jdbcType);
        c.setPrimaryKey(pk);
        return c;
    }

    // —— INSERT ——

    @Test
    void parsesInsertWithEscapesAndMultibyte() {
        String sql = "insert into \"SRC\".\"SPIKE_T\"(\"ID\",\"NAME\",\"NOTE\",\"TS\")"
                + " values ('1','it''s','hello 世界',TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午'));";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.INSERT, sql, spikeBinding());
        assertThat(c.op).isEqualTo(SqlRedoParser.Op.INSERT);
        assertThat(c.unsupportedReason).isNull();
        assertThat(c.row.get("ID")).isEqualTo("1");
        assertThat(c.row.get("NAME")).isEqualTo("it's");
        assertThat(c.row.get("NOTE")).isEqualTo("hello 世界");
        assertThat(c.row.get("TS")).isEqualTo("12-10月-26 04.29.23.261316 上午");
    }

    @Test
    void parsesInsertNull() {
        String sql = "insert into \"SRC\".\"SPIKE_T\"(\"ID\",\"NAME\",\"NOTE\",\"TS\")"
                + " values ('2',NULL,NULL,NULL);";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.INSERT, sql, spikeBinding());
        assertThat(c.row.get("ID")).isEqualTo("2");
        assertThat(c.row.get("NAME")).isNull();
        assertThat(c.row.get("NOTE")).isNull();
        assertThat(c.row.get("TS")).isNull();
    }

    // —— UPDATE ——

    @Test
    void parsesUpdateWithFullWhereImage() {
        // Real fixture shape: SET only the changed column; WHERE carries the full old
        // image (all columns + ROWID). v1.7.1 fix: the row map now merges the WHERE
        // old-image values so unchanged columns are not overwritten with NULL on the
        // target; SET assignments then override the merged old image. ROWID stays out.
        String sql = "update \"SRC\".\"SPIKE_T\" set \"NOTE\" = 'x' where \"ID\" = '1'"
                + " and \"NAME\" = 'Alice' and \"NOTE\" = 'old' and ROWID = 'AAAR4TAAYAAAAAPAAA';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.UPDATE, sql, spikeBinding());
        assertThat(c.op).isEqualTo(SqlRedoParser.Op.UPDATE);
        assertThat(c.unsupportedReason).isNull();
        // SET column wins over the WHERE old image.
        assertThat(c.row.get("NOTE")).isEqualTo("x");
        // Unchanged columns carry the WHERE old image (no NULL overwrite).
        assertThat(c.row.get("ID")).isEqualTo("1");
        assertThat(c.row.get("NAME")).isEqualTo("Alice");
        // ROWID is never a real column.
        assertThat(c.row).doesNotContainKey("ROWID");
    }

    @Test
    void updateMissingPkInWhereIsUnsupported() {
        String sql = "update \"SRC\".\"SPIKE_T\" set \"NOTE\" = 'x' where \"NAME\" = 'Alice';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.UPDATE, sql, spikeBinding());
        assertThat(c.op).isNull();
        assertThat(c.unsupportedReason).contains("where missing PK");
    }

    // —— DELETE ——

    @Test
    void parsesDeleteWithPkOnly() {
        String sql = "delete from \"SRC\".\"SPIKE_T\" where \"ID\" = '2'"
                + " and \"NAME\" = 'Bob' and ROWID = 'AAAR4TAAYAAAAAPAAB';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.DELETE, sql, spikeBinding());
        assertThat(c.op).isEqualTo(SqlRedoParser.Op.DELETE);
        assertThat(c.row.get("ID")).isEqualTo("2");
        // DELETE row map carries only the PK columns (the applier deletes by PK).
        assertThat(c.row).containsOnlyKeys("ID");
    }

    @Test
    void deleteMissingPkIsUnsupported() {
        String sql = "delete from \"SRC\".\"SPIKE_T\" where \"NAME\" = 'Bob';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.DELETE, sql, spikeBinding());
        assertThat(c.op).isNull();
        assertThat(c.unsupportedReason).contains("where missing PK");
    }

    // —— no-PK / edge cases ——

    @Test
    void noPkTableIsUnsupported() {
        TableMeta meta = new TableMeta();
        meta.setName("T");
        meta.getColumns().add(col("ID", Types.BIGINT, false));
        CdcTableBinding noPk = CdcTableBinding.of("SRC", "T", meta, "T", Collections.emptyMap());
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.INSERT,
                "insert into \"SRC\".\"T\"(\"ID\") values ('1');", noPk);
        assertThat(c.unsupportedReason).contains("no-pk");
    }

    @Test
    void emptySqlRedoIsUnsupported() {
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.INSERT, "", spikeBinding());
        assertThat(c.unsupportedReason).contains("empty");
    }

    // —— fixture replay ——

    /**
     * Replay the real SQL_REDO recorded by LogMinerPathSpikeIT on gvenzl/oracle-free:23.
     * Every line must parse (or land as a counted unsupported) — no exception escapes.
     */
    @Test
    void replaysRecordedFixture() throws Exception {
        List<String> lines = java.nio.file.Files.readAllLines(
                java.nio.file.Paths.get("src/test/resources/logminer/samples-23ai-free.txt"),
                java.nio.charset.StandardCharsets.UTF_8);
        SqlRedoParser.Op currentOp = null;
        int parsed = 0;
        int unsupported = 0;
        for (String line : lines) {
            if (line.startsWith("-- ")) {
                if (line.startsWith("-- INSERT")) currentOp = SqlRedoParser.Op.INSERT;
                else if (line.startsWith("-- UPDATE")) currentOp = SqlRedoParser.Op.UPDATE;
                else if (line.startsWith("-- DELETE")) currentOp = SqlRedoParser.Op.DELETE;
                continue;
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            assertThat(currentOp).as("fixture op header before body").isNotNull();
            SqlRedoParser.ParsedChange c = parser.parse(currentOp, line, spikeBinding());
            if (c.op == null) {
                unsupported++;
            } else {
                parsed++;
            }
        }
        // The fixture has 5 real changes; all should parse on the happy path.
        assertThat(parsed).as("all 5 fixture changes parse").isEqualTo(5);
        assertThat(unsupported).isZero();
    }
}
