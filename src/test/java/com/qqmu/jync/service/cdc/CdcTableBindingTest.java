package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * binding 是建表元数据的不可变快照:布局变了就重建 binding。因此
 * {@code columnNames()} / {@code typeByColumn()} 的结果在 binding 生命周期内是常量,
 * 每行、每个事务、每个解析器都重新分配一份只是浪费 —— LogMiner 的 redo 解析每行都调
 * {@code typeByColumn()}。
 */
class CdcTableBindingTest {

    @Test
    void columnNamesAreBuiltOnceAndCannotBeMutatedByCallers() {
        CdcTableBinding binding = orders();

        assertThat(binding.columnNames()).isSameAs(binding.columnNames());
        assertThat(binding.columnNames()).containsExactly("ID", "NAME");
        assertThatThrownBy(() -> binding.columnNames().add("EXTRA"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void typeByColumnIsBuiltOnceAndCannotBeMutatedByCallers() {
        CdcTableBinding binding = orders();

        assertThat(binding.typeByColumn()).isSameAs(binding.typeByColumn());
        assertThat(binding.typeByColumn()).containsEntry("ID", Types.BIGINT)
                .containsEntry("NAME", Types.VARCHAR);
        assertThatThrownBy(() -> binding.typeByColumn().put("EXTRA", Types.INTEGER))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static CdcTableBinding orders() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("ID", Types.BIGINT));
        meta.getColumns().add(column("NAME", Types.VARCHAR));
        meta.getPrimaryKeys().add("ID");
        return CdcTableBinding.of("app", "orders", meta, "orders", null);
    }

    private static ColumnMeta column(String name, int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        return column;
    }
}
