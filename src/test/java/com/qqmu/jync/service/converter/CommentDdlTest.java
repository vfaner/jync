package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;

/** Table and column comments must survive a target whose comments are separate DDL. */
class CommentDdlTest {

    private static TableMeta table() {
        TableMeta table = new TableMeta();
        table.setName("orders");
        table.setRemarks("订单主表");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setRemarks("主键");
        table.getColumns().add(id);
        return table;
    }

    private static String joined(List<String> ddl) {
        return ddl.stream().collect(Collectors.joining("\n"));
    }

    @Test
    void oracleFamilyGetsTableAndColumnComments() {
        for (SqlDialect dialect : List.of(new OracleDialect(), new OracleDialect(11))) {
            String ddl = joined(dialect.getCreatedTableCommentDdl(
                    table(), "APP", "orders", DatabaseType.MYSQL));
            assertThat(ddl).contains("COMMENT ON TABLE").contains("订单主表")
                    .contains("COMMENT ON COLUMN").contains("主键");
        }
    }

    @Test
    void postgresFamilyGetsTableAndColumnComments() {
        String ddl = joined(new PostgresDialect().getCreatedTableCommentDdl(
                table(), "public", "orders", DatabaseType.MYSQL));
        assertThat(ddl).contains("COMMENT ON TABLE").contains("订单主表")
                .contains("COMMENT ON COLUMN").contains("主键");
    }

    @Test
    void db2AndHanaUseTheSameStandardCommentSyntax() {
        for (SqlDialect dialect : List.of(new Db2Dialect(), new HanaSqlDialect())) {
            String ddl = joined(dialect.getCreatedTableCommentDdl(
                    table(), "APP", "orders", DatabaseType.MYSQL));
            assertThat(ddl).contains("COMMENT ON TABLE").contains("COMMENT ON COLUMN")
                    .contains("订单主表").contains("主键");
        }
    }

    @Test
    void sqlServerUsesExtendedPropertyUpserts() {
        String ddl = joined(new SqlServerDialect().getCreatedTableCommentDdl(
                table(), "dbo", "orders", DatabaseType.MYSQL));
        assertThat(ddl).contains("sp_updateextendedproperty")
                .contains("sp_addextendedproperty")
                .contains("MS_Description").contains("订单主表").contains("主键");
    }

    @Test
    void sqlServerClearsAnOldColumnCommentWhenTheSourceCommentIsRemoved() {
        ColumnMeta column = new ColumnMeta();
        column.setName("ID");
        String ddl = new SqlServerDialect().getColumnCommentDdl(
                "dbo", "orders", column, DatabaseType.MYSQL).get(0);
        assertThat(ddl).contains("sp_dropextendedproperty").doesNotContain("@value=");
    }
}
