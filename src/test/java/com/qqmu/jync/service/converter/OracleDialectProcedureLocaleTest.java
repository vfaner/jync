package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ProcedureMeta;

/**
 * Routine definitions arrive from {@code ALL_SOURCE} exactly as stored, so the first word can
 * be lower case. Recognising it decides how the body is wrapped: a definition that already
 * starts with PROCEDURE/FUNCTION is taken as the complete header, anything else is treated as
 * a bare PL/SQL body and gets a generated header.
 */
class OracleDialectProcedureLocaleTest {

    @Test
    void aLowercaseFunctionDefinitionIsStillTakenAsTheHeaderUnderATurkishDefaultLocale() {
        // tr-TR 的默认大写把 "function" 变成 "FUNCTİON"，与 "FUNCTION" 比对失败：
        // 函数体会被当成裸 PL/SQL，外面再包一层 "CREATE OR REPLACE FUNCTION ... AS"，
        // 生成的语句无法编译。
        ProcedureMeta p = new ProcedureMeta();
        p.setName("calc_tax");
        p.setDefinition("function calc_tax return number is begin return 1; end;");

        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(new OracleDialect().getCreateProcedureSql(p, "HR", null))
                    .containsExactly("CREATE OR REPLACE function calc_tax return number is "
                            + "begin return 1; end;");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
