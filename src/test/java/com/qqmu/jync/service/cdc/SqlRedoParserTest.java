package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class SqlRedoParserTest {

    private final SqlRedoParser parser = new SqlRedoParser();

    /** Build a binding for SPIKE_T (ID BIGINT PK, NAME VARCHAR, NOTE VARCHAR, TS TIMESTAMP). */
    /**
     * Binding for OG_LOB (ID NUMBER PK, CLOB_IN CLOB, BLOB_IN BLOB) — the docker-measured
     * out-of-line-LOB table of OracleToOpenGaussIT.streamedLobReachesANonOracleTarget.
     */
    private static CdcTableBinding lobBinding() {
        TableMeta meta = new TableMeta();
        meta.setName("OG_LOB");
        meta.setSchema("SRC");
        meta.getColumns().add(col("ID", Types.BIGINT, true));
        meta.getColumns().add(col("CLOB_IN", Types.CLOB, false));
        meta.getColumns().add(col("BLOB_IN", Types.BLOB, false));
        meta.getPrimaryKeys().add("ID");
        return CdcTableBinding.of("SRC", "OG_LOB", meta, "OG_LOB", Collections.emptyMap());
    }

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

    // —— 外联 LOB:LogMiner 的 SQL_REDO 里根本没有这一列 ——
    //
    // docker 实测(OracleToOpenGaussIT.streamedLobReachesANonOracleTarget):3000+ 字符的
    // CLOB 存在 LOB 段里(外联),Oracle 的补全日志不记录 LOB 列,于是这列既不在 SET 里
    // 也不在 WHERE 的旧镜像里。解析出来的行因此缺这一列,而 CdcEventApplier 按绑定里
    // 的全部列绑定(JdbcRowMapper 把缺失的键当 null)—— 目标上的现值被静默写成 NULL,
    // 任务状态还是 RUNNING,没有任何告警或计数。宁可跳过这一次变更并说清楚原因,
    // 也不能悄悄毁掉目标上的数据。

    @Test
    void updateOmittingAnOutOfLineLobColumnIsRefusedNotNulled() {
        String sql = "update \"SRC\".\"OG_LOB\" set \"BLOB_IN\" = HEXTORAW('deadbeef')"
                + " where \"ID\" = '1' and ROWID = 'AAAR4TAAYAAAAALAAA';";

        SqlRedoParser.ParsedChange c =
                parser.parse(SqlRedoParser.Op.UPDATE, sql, lobBinding());

        assertThat(c.op).isNull();
        assertThat(c.unsupportedReason).contains("CLOB_IN");
    }

    @Test
    void insertOmittingAnOutOfLineLobColumnIsRefusedNotNulled() {
        String sql = "insert into \"SRC\".\"OG_LOB\"(\"ID\",\"BLOB_IN\")"
                + " values ('2',HEXTORAW('cafe'));";

        SqlRedoParser.ParsedChange c =
                parser.parse(SqlRedoParser.Op.INSERT, sql, lobBinding());

        assertThat(c.op).isNull();
        assertThat(c.unsupportedReason).contains("CLOB_IN");
    }

    /**
     * The refusal has to say <em>which</em> columns were missing, not just "unsupported":
     * the CDC pipeline counts and warns about out-of-line LOBs separately from every other
     * unparseable statement, and that classification comes from this list.
     */
    @Test
    void aRefusalCausedByMissingColumnsNamesThem() {
        String sql = "update \"SRC\".\"OG_LOB\" set \"BLOB_IN\" = HEXTORAW('deadbeef')"
                + " where \"ID\" = '1' and ROWID = 'AAAR4TAAYAAAAALAAA';";

        SqlRedoParser.ParsedChange c =
                parser.parse(SqlRedoParser.Op.UPDATE, sql, lobBinding());

        assertThat(c.missingColumns).containsExactly("CLOB_IN");
    }

    /** Every other refusal is a parse problem, not a missing-LOB problem. */
    @Test
    void otherRefusalsCarryNoMissingColumns() {
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.UPDATE,
                "update \"SRC\".\"SPIKE_T\" set \"NAME\" = 'x';", spikeBinding());

        assertThat(c.op).isNull();
        assertThat(c.missingColumns).isEmpty();
    }

    @Test
    void updateCarryingUnchangedColumnsInTheWhereStillParses() {
        // 守护:补全日志生效时,未变更的列会出现在 WHERE 的旧镜像里(实机样本见下),
        // 整套解析必须照旧工作 —— 只有真的缺列才拒绝。
        String sql = "update \"SRC\".\"OG_LOB\" set \"CLOB_IN\" = 'x'"
                + " where \"ID\" = '1' and \"BLOB_IN\" = HEXTORAW('aabb')"
                + " and ROWID = 'AAAR4TAAYAAAAALAAA';";

        SqlRedoParser.ParsedChange c =
                parser.parse(SqlRedoParser.Op.UPDATE, sql, lobBinding());

        assertThat(c.unsupportedReason).isNull();
        assertThat(c.row).containsKeys("ID", "CLOB_IN", "BLOB_IN");
    }

    // —— INSERT ——    @Test
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
                + " and \"NAME\" = 'Alice' and \"NOTE\" = 'old'"
                + " and \"TS\" = TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')"
                + " and ROWID = 'AAAR4TAAYAAAAAPAAA';";
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
    void updateOldImageNullColumnIsParsedWithIsNullPredicate() {
        // LogMiner 重建旧镜像时,值为 NULL 的列渲染成 `"COL" IS NULL`(Oracle 官方
        // V$LOGMNR_CONTENTS 的 SQL_REDO 样本:… and "COMMISSION_PCT" IS NULL …),
        // 不会写成 "= NULL"。parseWhereColumns 只认 '=',该列因此从行里消失,
        // refuseIfColumnsAreMissing 把整条 UPDATE 误判成"缺列"(行外 LOB 那一类):
        // 变更被静默丢弃、计入 missing-Lob 计数与 parseFailures —— 连续 6 条就把
        // 健康项目 PARK 成 PARSE_STRIKE_OUT。任何含可空列(且当前为 NULL)的表,
        // 只要开了 SUPPLEMENTAL LOG DATA (ALL) COLUMNS 就会命中。
        String sql = "update \"SRC\".\"SPIKE_T\" set \"NOTE\" = 'x' where \"ID\" = '1'"
                + " and \"NAME\" IS NULL"
                + " and \"NOTE\" = 'old'"
                + " and \"TS\" = TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')"
                + " and ROWID = 'AAAR4TAAYAAAAAPAAA';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.UPDATE, sql, spikeBinding());
        assertThat(c.unsupportedReason).isNull();
        assertThat(c.op).isEqualTo(SqlRedoParser.Op.UPDATE);
        // 列存在且值为 null —— 不是"缺列",目标端写回 NULL 与旧值一致。
        assertThat(c.row).containsKey("NAME");
        assertThat(c.row.get("NAME")).isNull();
        assertThat(c.row.get("NOTE")).isEqualTo("x");
    }

    @Test
    void updateMissingPkInWhereIsUnsupported() {
        String sql = "update \"SRC\".\"SPIKE_T\" set \"NOTE\" = 'x' where \"NAME\" = 'Alice';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.UPDATE, sql, spikeBinding());
        assertThat(c.op).isNull();
        assertThat(c.unsupportedReason).contains("where missing PK");
    }

    // —— P1-8: UPDATE 旧镜像字面量转换失败不得静默 ——

    @Test
    void updateOldImageConversionFailureWarnsAndStillFallsBackToRawString() {
        // TS 列的旧镜像是一个 parser 不认识的函数字面量(TO_BLOB(...)) →
        // convert 抛 UnsupportedLiteralException。降级语义必须保留(整条 UPDATE
        // 不因一列旧镜像报废,原样字符串透传),但必须留一条点名该列的 WARN——
        // 静默降级会让目标端的数据损坏无从诊断。
        ListAppender<ILoggingEvent> appender = LogCapture.attach(SqlRedoParser.class);
        try {
            String sql = "update \"SRC\".\"SPIKE_T\" set \"NOTE\" = 'x' where \"ID\" = '1'"
                    + " and \"NAME\" = 'Alice' and \"NOTE\" = 'old'"
                    + " and \"TS\" = TO_BLOB('DEAD') and ROWID = 'AAAR4TAAYAAAAAPAAA';";
            SqlRedoParser.ParsedChange c =
                    parser.parse(SqlRedoParser.Op.UPDATE, sql, spikeBinding());
            assertThat(c.op).isEqualTo(SqlRedoParser.Op.UPDATE);
            assertThat(c.row.get("TS")).isEqualTo("TO_BLOB('DEAD')");
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("TS"));
        } finally {
            LogCapture.detach(SqlRedoParser.class, appender);
        }
    }

    // —— P1-8b(RED 发现的真实数据损坏 bug):splitOnAnd off-by-one ——

    @Test
    void updateOldImageOfColumnBeforeRowidSurvivesTheSplit() {
        // splitOnAnd 曾在 i+4 处查词边界(应为 i+3)——" and ROWID"(AND 后跟未加
        // 引号 token)永远不拆分,ROWID 前最后一个真实列的旧镜像被粘上整条尾巴。
        // 既有 fixture 恰好都被 SET 覆盖而掩盖了它。这里让 NAME 成为受害者且不进 SET:
        // 若不拆,目标端该列会收到 "'Alice' and ROWID = '...'" 这样的垃圾串。
        String sql = "update \"SRC\".\"SPIKE_T\" set \"NOTE\" = 'x' where \"ID\" = '1'"
                + " and \"TS\" = TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')"
                + " and \"NAME\" = 'Alice' and ROWID = 'AAAR4TAAYAAAAAPAAA';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.UPDATE, sql, spikeBinding());
        assertThat(c.op).isEqualTo(SqlRedoParser.Op.UPDATE);
        assertThat(c.row.get("NAME")).isEqualTo("Alice");
        assertThat(c.row.get("TS")).isEqualTo("12-10月-26 04.29.23.261316 上午");
        assertThat(c.row).doesNotContainKey("ROWID");
    }

    @Test
    void splitOnAndTreatsAndAsWordTokenAtBothBoundaries() {
        // H2 形态:AND 前无空白;LogMiner 形态:一个空格后跟未加引号的 ROWID。
        assertThat(SqlRedoParser.splitOnAnd("\"A\"='1'AND \"B\"='2'")).hasSize(2);
        assertThat(SqlRedoParser.splitOnAnd("\"A\"='1' AND ROWID='x'")).hasSize(2);
        assertThat(SqlRedoParser.splitOnAnd("\"A\"='1'AND\"B\"='2'")).hasSize(2);
        // 字符串值内部与标识符内部的 "and" 不得触发拆分。
        assertThat(SqlRedoParser.splitOnAnd("\"A\"='x and y'")).hasSize(1);
        assertThat(SqlRedoParser.splitOnAnd("\"BAND\"='1'")).hasSize(1);
    }

    // —— P2-6:Oracle q'[...]' 替代引号(内容可含裸单引号,旧状态机会提前"出引号") ——

    @Test
    void splitTopLevelKeepsCommaInsideQQuotedLiteral() {
        // q'[it's, fine]':it's 里的撇号让旧状态机在串内"出引号",后面的逗号被当
        // 顶层分隔符 → SET 赋值列表错位(列/值错配)。q-quote 感知后必须是 2 段。
        List<String> parts = SqlRedoParser.splitTopLevel("q'[it's, fine]', 'z'", ',');
        assertThat(parts).containsExactly("q'[it's, fine]'", " 'z'");
    }

    @Test
    void indexOfTopLevelSkipsEqualsInsideQQuotedLiteral() {
        assertThat(SqlRedoParser.indexOfTopLevel("q'[it's=a]'", '=')).isEqualTo(-1);
    }

    @Test
    void splitOnAndDoesNotSplitInsideQQuotedLiteral() {
        // 撇号先于 and 出现:旧状态机在 it's 处出引号,串内 and 被当顶层合取词。
        // (只断言段数不够:失控的引号状态会把尾巴重新"吞"回去,段数巧合相同——
        // 必须断言内容:NOTE 段保留完整 q-quote 字面量,ROWID 段不被粘走。)
        List<String> parts = SqlRedoParser.splitOnAnd(
                "\"ID\"='1' and \"NOTE\"=q'[it's x and y]' and ROWID='r'");
        assertThat(parts).hasSize(3);
        assertThat(parts.get(1)).contains("q'[it's x and y]'");
        assertThat(parts.get(2)).contains("ROWID");
    }

    @Test
    void updateOldImageQQuotedValueSurvivesParseAndConversion() {
        // 端到端:q-quote 旧镜像值含撇号+and;同时钉住 convert 的 q-quote 去壳。
        String sql = "UPDATE \"SRC\".\"SPIKE_T\" SET \"NAME\"='bob' WHERE \"ID\"='1'"
                + " and \"NOTE\"=q'[it's x and y]'"
                + " and \"TS\"=TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')"
                + " and ROWID='AAAR4TAAYAAAAAPAAA';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.UPDATE, sql, spikeBinding());
        assertThat(c.op).isEqualTo(SqlRedoParser.Op.UPDATE);
        assertThat(c.row.get("NOTE")).isEqualTo("it's x and y");
        assertThat(c.row.get("NAME")).isEqualTo("bob");
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

    // —— 值里出现独立单词 where ——

    /**
     * SQL_REDO 的值原样带引号渲染,列里存 SQL 文本或说明文字时 SET 值可能含有独立的
     * "where"。indexOfWord 不做引号感知,会把值里的 where 当成 WHERE 关键字切开,
     * SET 部分以未闭合的 {@code 'go} 结尾,字面量转换失败,整条 UPDATE 被跳过
     * (计数,连续 6 次后整任务停机),目标行保持旧值。
     */
    @Test
    void updateWhoseSetValueContainsAStandaloneWhereWordStillParses() {
        String sql = "update \"SRC\".\"SPIKE_T\" set \"NOTE\" = 'go where now'"
                + " where \"ID\" = '1' and \"NAME\" = 'Alice'"
                + " and \"TS\" = TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')"
                + " and ROWID = 'AAAR4TAAYAAAAAPAAA';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.UPDATE, sql, spikeBinding());
        assertThat(c.unsupportedReason).isNull();
        assertThat(c.op).isEqualTo(SqlRedoParser.Op.UPDATE);
        assertThat(c.row.get("NOTE")).isEqualTo("go where now");
        assertThat(c.row.get("ID")).isEqualTo("1");
    }

    // —— 值/标识符里的 U+0130(İ)不得让关键字扫描错位 ——

    /**
     * indexOfWord 过去在 {@code sql.toLowerCase(Locale.ROOT)} 的副本上找关键字，却用
     * 副本里的下标去切原串。U+0130(带点大写 I)在 ROOT 下展开成 "i"+组合点两个字符，
     * 副本从此比原串长一位：WHERE 关键字的下标整体后移，SET 片段末尾粘上 "w"，
     * 字面量转换失败，整条 UPDATE 被拒绝（计数，连续 5 次后整任务停机），
     * 目标行保持旧值。土耳其语数据里 "İstanbul" 这类值并不罕见。
     */
    @Test
    void updateWhoseSetValueContainsADottedCapitalIStillParses() {
        String sql = "update \"SRC\".\"SPIKE_T\" set \"NOTE\" = 'İstanbul'"
                + " where \"ID\" = '1' and \"NAME\" = 'Alice'"
                + " and \"TS\" = TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')"
                + " and ROWID = 'AAAR4TAAYAAAAAPAAA';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.UPDATE, sql, spikeBinding());
        assertThat(c.unsupportedReason).isNull();
        assertThat(c.op).isEqualTo(SqlRedoParser.Op.UPDATE);
        assertThat(c.row.get("NOTE")).isEqualTo("İstanbul");
        assertThat(c.row.get("ID")).isEqualTo("1");
    }

    /**
     * splitOnAnd 有同样的病灶：它在 wherePart 的小写副本上匹配 "and"，用副本下标推进
     * 原串的扫描。旧镜像里出现 U+0130 之后，后续的 " and " 全部匹配不上，合取项被粘成
     * 一段——未变更的列从旧镜像里消失，整条 UPDATE 被拒绝。
     */
    @Test
    void updateWhoseOldImageContainsADottedCapitalIStillSplitsConjuncts() {
        String sql = "update \"SRC\".\"SPIKE_T\" set \"NOTE\" = 'x' where \"ID\" = '1'"
                + " and \"NAME\" = 'İstanbul'"
                + " and \"TS\" = TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')"
                + " and ROWID = 'AAAR4TAAYAAAAAPAAA';";
        SqlRedoParser.ParsedChange c = parser.parse(SqlRedoParser.Op.UPDATE, sql, spikeBinding());
        assertThat(c.unsupportedReason).isNull();
        assertThat(c.op).isEqualTo(SqlRedoParser.Op.UPDATE);
        assertThat(c.row.get("NAME")).isEqualTo("İstanbul");
        assertThat(c.row.get("TS")).isEqualTo("12-10月-26 04.29.23.261316 上午");
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
