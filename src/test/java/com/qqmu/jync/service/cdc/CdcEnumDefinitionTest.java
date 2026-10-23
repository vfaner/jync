package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * MySQL ENUM / SET 列在 binlog 里的取值形态:连接器只送序号(ENUM → Integer)或位掩码
 * (SET → Long),标签列表根本不在 binlog 里,只能来自 information_schema 的 COLUMN_TYPE。
 * 其余所有读取路径(getString / JdbcValueNormalizer)送的都是标签文本;不转换就会把
 * 序号/掩码静默写进目标 VARCHAR 列。
 */
class CdcEnumDefinitionTest {

    @Test
    void parsesAnEnumLabelList() {
        CdcEnumDefinition def = CdcEnumDefinition.parse("enum('a','b','c')");
        assertThat(def).isNotNull();
        assertThat(def.isSet()).isFalse();
        assertThat(def.labels()).containsExactly("a", "b", "c");
    }

    @Test
    void parsesEscapedQuoteAndEmptyLabel() {
        CdcEnumDefinition def = CdcEnumDefinition.parse("enum('it''s','', 'x, y')");
        assertThat(def.labels()).containsExactly("it's", "", "x, y");
    }

    @Test
    void parsesASetDefinitionCaseInsensitively() {
        CdcEnumDefinition def = CdcEnumDefinition.parse("SET('read','write')");
        assertThat(def.isSet()).isTrue();
        assertThat(def.labels()).containsExactly("read", "write");
    }

    @Test
    void nonEnumColumnTypesAreNotDefinitions() {
        assertThat(CdcEnumDefinition.parse("varchar(64)")).isNull();
        assertThat(CdcEnumDefinition.parse(null)).isNull();
        assertThat(CdcEnumDefinition.parse("enum(")).isNull();
        assertThat(CdcEnumDefinition.parse("enum(abc)")).isNull();
    }

    @Test
    void enumOrdinalRendersTheLabel() {
        assertThat(CdcEnumDefinition.parse("enum('a','b','c')").render(2)).isEqualTo("b");
    }

    @Test
    void enumIndexZeroRendersTheEmptyValue() {
        // 非严格模式可写入的"无效枚举值"(序号 0):getString 读到的是空串。
        assertThat(CdcEnumDefinition.parse("enum('a','b')").render(0)).isEqualTo("");
    }

    @Test
    void setBitmaskRendersTheCommaJoinedLabels() {
        CdcEnumDefinition def = CdcEnumDefinition.parse("set('read','write','exec')");
        assertThat(def.render(1L)).isEqualTo("read");
        assertThat(def.render(3L)).isEqualTo("read,write");
        assertThat(def.render(0L)).isEqualTo("");
    }

    @Test
    void outOfRangeOrdinalIsRejected() {
        assertThatThrownBy(() -> CdcEnumDefinition.parse("enum('a','b')").render(3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setBitBeyondTheLabelListIsRejected() {
        assertThatThrownBy(() -> CdcEnumDefinition.parse("set('a','b')").render(4L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
