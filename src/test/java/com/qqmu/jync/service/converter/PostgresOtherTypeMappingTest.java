package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * P2: PostgreSQL reports uuid/json/jsonb/xml/enums/arrays as Types.OTHER. A non-PG target must
 * receive portable types instead of a CREATE using names like "uuid" or "_int4".
 */
class PostgresOtherTypeMappingTest {

    private ColumnMeta other(String typeName) {
        ColumnMeta column = new ColumnMeta();
        column.setName("X");
        column.setJdbcType(Types.OTHER);
        column.setTypeName(typeName);
        column.setSize(0);
        return column;
    }

    private ColumnMeta array(String typeName) {
        ColumnMeta column = new ColumnMeta();
        column.setName("X");
        column.setJdbcType(Types.ARRAY);
        column.setTypeName(typeName);
        column.setSize(0);
        return column;
    }

    @Test
    void pgArraysNeverEmitTheInternalUnderScoreTypeName() {
        // Types.ARRAY used to fall through mapType unhandled, emitting the raw internal
        // name "_int4"/"_text" — legal nowhere except a PG-family target by accident, and
        // even there array VALUES arrive as text. Every target must build a text LOB.
        String generic = new GenericSqlDialect().mapType(array("_int4"),
                DatabaseType.POSTGRESQL);
        String mysql = new MySqlDialect().mapType(array("_text"), DatabaseType.POSTGRESQL);
        String pg = new PostgresDialect().mapType(array("_int4"), DatabaseType.POSTGRESQL);
        for (String ddl : List.of(generic, mysql, pg)) {
            assertThat(ddl).doesNotContain("_int4").doesNotContain("_text");
            assertThat(ddl.toUpperCase()).containsAnyOf("CLOB", "TEXT", "LONGTEXT");
        }
    }

    @Test
    void uuidMapsToVarcharOnAGenericTarget() {
        String mapped = new GenericSqlDialect().mapType(other("uuid"),
                DatabaseType.POSTGRESQL);
        assertThat(mapped.toUpperCase()).contains("VARCHAR").contains("36");
    }

    @Test
    void jsonbMapsToClobOnAGenericTarget() {
        assertThat(new GenericSqlDialect().mapType(other("jsonb"),
                DatabaseType.POSTGRESQL)).isEqualTo("CLOB");
    }

    @Test
    void jsonMapsToNativeJsonOnMySql() {
        assertThat(new MySqlDialect().mapType(other("json"), DatabaseType.POSTGRESQL))
                .isEqualTo("JSON");
    }

    @Test
    void pgArraysAndXmlMoveAsTextOnMySql() {
        assertThat(new MySqlDialect().mapType(other("_int4"), DatabaseType.POSTGRESQL))
                .isEqualTo("LONGTEXT");
        assertThat(new MySqlDialect().mapType(other("xml"), DatabaseType.POSTGRESQL))
                .isEqualTo("LONGTEXT");
    }

    @Test
    void truncateDoesNotCarryCascade() {
        String sql = new PostgresDialect().getTruncateSql("public", "T");
        assertThat(sql).doesNotContain("CASCADE");
    }
}
