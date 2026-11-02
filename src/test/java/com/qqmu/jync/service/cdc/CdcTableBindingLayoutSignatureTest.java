package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * {@link CdcTableBinding#layoutSignature(TableMeta)} is the persisted half of the DDL-drift
 * defence: a restart compares it against the catalog it reads now. It must therefore change
 * exactly when a positional value would move to another column — add / drop / rename / reorder
 * — and stay stable for a type-only change, which moves nothing.
 */
class CdcTableBindingLayoutSignatureTest {

    private static ColumnMeta column(String name, int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        return column;
    }

    private static String signatureOf(ColumnMeta... columns) {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        for (ColumnMeta column : columns) {
            meta.getColumns().add(column);
        }
        return CdcTableBinding.layoutSignature(meta);
    }

    @Test
    void theOrderOfTheColumnsIsPartOfTheSignature() {
        String ordered = signatureOf(column("id", Types.BIGINT),
                column("name", Types.VARCHAR));
        String reordered = signatureOf(column("name", Types.VARCHAR),
                column("id", Types.BIGINT));

        assertThat(reordered).isNotEqualTo(ordered);
    }

    @Test
    void addDropAndRenameChangeTheSignature() {
        String original = signatureOf(column("id", Types.BIGINT),
                column("name", Types.VARCHAR));

        assertThat(signatureOf(column("id", Types.BIGINT), column("name", Types.VARCHAR),
                column("phone", Types.VARCHAR))).isNotEqualTo(original);
        assertThat(signatureOf(column("id", Types.BIGINT))).isNotEqualTo(original);
        assertThat(signatureOf(column("id", Types.BIGINT),
                column("full_name", Types.VARCHAR))).isNotEqualTo(original);
    }

    @Test
    void aTypeOnlyChangeKeepsTheSignature() {
        // 类型变化不移动位置,不该触发重新基线。
        assertThat(signatureOf(column("id", Types.BIGINT), column("name", Types.VARCHAR)))
                .isEqualTo(signatureOf(column("id", Types.INTEGER),
                        column("name", Types.LONGVARCHAR)));
    }

    @Test
    void mysqlColumnNameCaseDoesNotMatter() {
        assertThat(signatureOf(column("ID", Types.BIGINT), column("NAME", Types.VARCHAR)))
                .isEqualTo(signatureOf(column("id", Types.BIGINT),
                        column("name", Types.VARCHAR)));
    }

    @Test
    void aTableWithoutMetadataHasNoSignature() {
        assertThat(CdcTableBinding.layoutSignature(null)).isNull();
    }
}
