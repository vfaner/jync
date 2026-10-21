package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * MySQL DDL shape that only shows up on real migrations:
 *
 * <ul>
 *   <li>Column comments are part of the MySQL column definition and must travel with it —
 *       a structure sync used to create tables whose table comment was copied but whose
 *       column comments were silently dropped.</li>
 *   <li>A wide Oracle table (dozens of {@code VARCHAR2(4000)} columns) exceeds InnoDB's
 *       65535-byte row-size limit and CREATE TABLE fails with error 1118. The columns that
 *       cannot fit have to land as LOBs instead of failing the whole table.</li>
 * </ul>
 */
class MySqlTableDdlTest {

    private final MySqlDialect dialect = new MySqlDialect();

    @Test
    void columnCommentsArePartOfTheColumnDefinition() {
        TableMeta table = table(column("ID", Types.BIGINT, 0, "主键"));
        table.getColumns().add(column("NAME", Types.VARCHAR, 50, "客户名称"));

        String sql = dialect.getCreateTableSql(table, "db", "t", DatabaseType.ORACLE);

        assertThat(sql).contains("`ID` BIGINT COMMENT '主键'");
        assertThat(sql).contains("`NAME` VARCHAR(50) COMMENT '客户名称'");
    }

    @Test
    void aCommentIsEscapedAndFittedToMySqlsLengthLimit() {
        String longRemark = "a".repeat(1100);
        TableMeta table = table(column("ID", Types.BIGINT, 0, "it's a \\ comment"));

        String sql = dialect.getCreateTableSql(table, "db", "t", DatabaseType.ORACLE);
        assertThat(sql).contains("COMMENT 'it''s a \\\\ comment'");

        table.getColumns().get(0).setRemarks(longRemark);
        String truncated = dialect.getCreateTableSql(table, "db", "t", DatabaseType.ORACLE);
        assertThat(truncated).contains("COMMENT '" + "a".repeat(1024) + "'");
        assertThat(truncated).doesNotContain("a".repeat(1025));
    }

    @Test
    void addedColumnsCarryTheirCommentToo() {
        ColumnMeta column = column("NOTE", Types.VARCHAR, 100, "备注");

        String sql = dialect.getAddColumnSql("db", "t", column, DatabaseType.ORACLE);

        assertThat(sql).contains("COMMENT '备注'");
    }

    @Test
    void aWideVarcharTableStaysInsideTheRowSizeLimit() {
        TableMeta table = new TableMeta();
        table.setName("BO_EU_LOAN_DAILY");
        for (int i = 1; i <= 30; i++) {
            table.getColumns().add(column("C" + i, Types.VARCHAR, 4000, null));
        }

        String sql = dialect.getCreateTableSql(table, "db", "BO_EU_LOAN_DAILY",
                DatabaseType.ORACLE);

        // utf8mb4 VARCHAR(4000) is 16002 bytes: three of them fit in the 60000 budget.
        assertThat(count(sql, "VARCHAR(4000)")).isEqualTo(3);
        assertThat(count(sql, "LONGTEXT")).isEqualTo(27);
        assertThat(sql).contains("`C1`").contains("`C30`");
    }

    @Test
    void aSmallTableIsLeftExactlyAsBefore() {
        TableMeta table = table(column("ID", Types.BIGINT, 0, null));
        table.getColumns().add(column("NAME", Types.VARCHAR, 50, null));

        String sql = dialect.getCreateTableSql(table, "db", "t", DatabaseType.ORACLE);

        assertThat(sql).doesNotContain("LONGTEXT");
        assertThat(sql).contains("`NAME` VARCHAR(50)");
    }

    @Test
    void keyColumnsSurviveWhileNonKeyColumnsAbsorbTheOverflow() {
        TableMeta table = new TableMeta();
        table.setName("WIDE");
        ColumnMeta pk = column("CODE", Types.VARCHAR, 4000, null);
        pk.setPrimaryKey(true);
        table.getColumns().add(pk);
        table.setPrimaryKeys(new ArrayList<>());
        table.getPrimaryKeys().add("CODE");
        for (int i = 1; i <= 10; i++) {
            table.getColumns().add(column("C" + i, Types.VARCHAR, 4000, null));
        }

        String sql = dialect.getCreateTableSql(table, "db", "WIDE", DatabaseType.ORACLE);

        // 11 x 16002 bytes = 176022. Each conversion saves 15982 bytes (16002 - the 20-byte
        // off-page pointer), so eight of them (176022 - 8 x 15982 = 48166) bring the row
        // inside the 60000 budget; seven leave 64148. The primary key must stay indexable,
        // and it does: all eight are non-key.
        assertThat(sql).contains("`CODE` VARCHAR(4000)");
        assertThat(count(sql, "LONGTEXT")).isEqualTo(8);
    }

    @Test
    void aTableWithAHundredPlusBusinessColumnsStillProducesAValidRowSize() {
        TableMeta table = new TableMeta();
        table.setName("BIZ_120");
        for (int i = 1; i <= 120; i++) {
            table.getColumns().add(column("BIZ_" + i, Types.VARCHAR, 200, null));
        }

        String sql = dialect.getCreateTableSql(table, "db", "BIZ_120", DatabaseType.ORACLE);

        // 120 x 802 bytes = 96240; each conversion to LONGTEXT saves 782, so 47 columns have
        // to become LONGTEXT and 73 keep their VARCHAR(200). The column count itself is far
        // below InnoDB's 1017-column ceiling, so nothing else blocks the CREATE.
        assertThat(count(sql, "LONGTEXT")).isEqualTo(47);
        assertThat(count(sql, "VARCHAR(200)")).isEqualTo(73);
        assertThat(sql).contains("`BIZ_1`").contains("`BIZ_120`");
    }

    @Test
    void aUniqueKeyColumnIsNarrowedOnlyWhenNothingElseCanMakeRoom() {
        TableMeta table = new TableMeta();
        table.setName("WIDE_UNIQUE");
        List<IndexMeta> indexes = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            table.getColumns().add(column("U" + i, Types.VARCHAR, 4000, null));
            indexes.add(index("UK_U" + i, "U" + i, true));
        }
        table.setIndexes(indexes);

        String sql = dialect.getCreateTableSql(table, "db", "WIDE_UNIQUE", DatabaseType.ORACLE);

        // Data comes first: 10 x 16002 bytes need seven conversions, and every column is
        // unique, so the table is narrowed rather than left unable to sync at all.
        assertThat(count(sql, "LONGTEXT")).isEqualTo(7);

        // The index is a different matter: a TEXT column cannot carry a full UNIQUE index,
        // and a prefix would enforce uniqueness on the first 191 characters only — silently
        // rejecting rows the source accepts. Null tells the caller to skip it.
        assertThat(dialect.getCreateIndexSql(indexes.get(0), "db", "WIDE_UNIQUE", table,
                DatabaseType.ORACLE)).isNull();
    }

    @Test
    void ordinaryIndexedColumnsAreNarrowedBeforeAUniqueOne() {
        TableMeta table = new TableMeta();
        table.setName("MIXED");
        table.getColumns().add(column("U1", Types.VARCHAR, 4000, null));
        for (int i = 2; i <= 10; i++) {
            table.getColumns().add(column("C" + i, Types.VARCHAR, 4000, null));
        }
        table.setIndexes(List.of(index("UK_U1", "U1", true)));

        String sql = dialect.getCreateTableSql(table, "db", "MIXED", DatabaseType.ORACLE);

        // Nine unindexed columns can absorb the whole overflow, so the unique column keeps
        // its type and its index can still be created exactly as defined.
        assertThat(sql).contains("`U1` VARCHAR(4000)");
        assertThat(count(sql, "LONGTEXT")).isEqualTo(7);
        assertThat(dialect.getCreateIndexSql(table.getIndexes().get(0), "db", "MIXED", table,
                DatabaseType.ORACLE)).isNotNull();
    }

    @Test
    void anIndexOnANarrowedColumnIsCreatedAsAPrefixIndex() {
        TableMeta table = new TableMeta();
        table.setName("WIDE_INDEXED");
        List<IndexMeta> indexes = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            table.getColumns().add(column("C" + i, Types.VARCHAR, 2000, null));
            if (i >= 2 && i <= 18) {
                indexes.add(index("IDX_C" + i, "C" + i, false));
            }
        }
        IndexMeta mixed = index("IDX_MIXED", "C1", false);
        mixed.setColumns(List.of("C1", "C18"));
        indexes.add(mixed);
        table.setIndexes(indexes);

        String create = dialect.getCreateIndexSql(mixed, "db", "WIDE_INDEXED", table,
                DatabaseType.ORACLE);

        // C19 and C20 are narrowed first, then C1..C11 to reach the budget; C18 (also 8002
        // bytes) stays a VARCHAR. MySQL rejects a key on a TEXT column without a length, so
        // the narrowed column needs the prefix while the narrow one must stay exact.
        assertThat(create).contains("`C1`(191)");
        assertThat(create).contains("`C18`");
        assertThat(create).doesNotContain("`C18`(191)");
    }

    @Test
    void addingAColumnToANarrowedTableUsesTheSameNarrowingPlan() {
        TableMeta table = new TableMeta();
        table.setName("WIDE_GROWN");
        for (int i = 1; i <= 10; i++) {
            table.getColumns().add(column("C" + i, Types.VARCHAR, 2000, null));
        }
        ColumnMeta added = column("C_NEW", Types.VARCHAR, 4000, null);
        table.getColumns().add(added);

        String sql = dialect.getAddColumnSql("db", "WIDE_GROWN", added, DatabaseType.ORACLE,
                table);

        // 10 x 8002 + 16002 = 96022 bytes: the widest column (the new one) is narrowed
        // first, so adding it as VARCHAR(4000) would break the budget the table already met.
        assertThat(sql).contains("`C_NEW` LONGTEXT");
    }

    @Test
    void modifyingANarrowedColumnKeepsItNarrow() {
        TableMeta table = new TableMeta();
        table.setName("WIDE");
        for (int i = 1; i <= 10; i++) {
            table.getColumns().add(column("C" + i, Types.VARCHAR, 4000, null));
        }
        ColumnMeta first = table.getColumns().get(0);
        first.setRemarks("new comment");

        String sql = dialect.getModifyColumnSql("db", "WIDE", first, DatabaseType.ORACLE, table);

        // A comment edit lands here as a MODIFY COLUMN; reissuing the wide source type
        // would push the table straight back over the row-size limit.
        assertThat(sql).contains("`C1` LONGTEXT").contains("COMMENT 'new comment'");
    }

    @Test
    void aNarrowedColumnDoesNotCarryADefaultMySqlRejects() {
        TableMeta table = new TableMeta();
        table.setName("WIDE_DEFAULTS");
        for (int i = 1; i <= 10; i++) {
            table.getColumns().add(column("C" + i, Types.VARCHAR, 4000, null));
        }
        ColumnMeta first = table.getColumns().get(0);
        first.setDefaultValue("' '");
        first.setNullable(false);

        String sql = dialect.getCreateTableSql(table, "db", "WIDE_DEFAULTS",
                DatabaseType.ORACLE);

        // MySQL rejects DEFAULT on TEXT/BLOB (error 1101 on 5.7, and 8.0 only takes an
        // expression default), so copying the source default onto the narrowed column
        // would fail the whole CREATE TABLE.
        assertThat(sql).contains("`C1` LONGTEXT NOT NULL,\n");
        assertThat(sql).doesNotContain("DEFAULT ' '");
    }

    @Test
    void aColumnThatWasNotNarrowedKeepsItsDefault() {
        TableMeta table = table(column("ID", Types.BIGINT, 0, null));
        ColumnMeta name = column("NAME", Types.VARCHAR, 50, null);
        name.setDefaultValue("'x'");
        table.getColumns().add(name);

        String sql = dialect.getCreateTableSql(table, "db", "t", DatabaseType.ORACLE);

        assertThat(sql).contains("`NAME` VARCHAR(50) DEFAULT 'x'");
    }

    @Test
    void anExistingTablesCommentCanBeChangedOrCleared() {
        assertThat(dialect.getAlterTableCommentSql("db", "t", "订单主表"))
                .isEqualTo("ALTER TABLE `db`.`t` COMMENT='订单主表'");
        // A comment removed at the source must not linger on the target.
        assertThat(dialect.getAlterTableCommentSql("db", "t", null))
                .isEqualTo("ALTER TABLE `db`.`t` COMMENT=''");
        assertThat(dialect.getAlterTableCommentSql("db", "t", "x".repeat(3000)))
                .contains("COMMENT='" + "x".repeat(2048) + "'")
                .doesNotContain("x".repeat(2049));
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static TableMeta table(ColumnMeta first) {
        TableMeta table = new TableMeta();
        table.setName("t");
        table.setColumns(new ArrayList<>(List.of(first)));
        return table;
    }

    private static IndexMeta index(String name, String column, boolean unique) {
        IndexMeta index = new IndexMeta();
        index.setName(name);
        index.setUnique(unique);
        index.setColumns(List.of(column));
        return index;
    }

    private static ColumnMeta column(String name, int jdbcType, int size, String remarks) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setTypeName(jdbcType == Types.VARCHAR ? "VARCHAR" : "NUMBER");
        if (size > 0) {
            column.setSize(size);
        }
        column.setRemarks(remarks);
        return column;
    }
}
