package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.DatabaseConfigService;

/**
 * A SQL source may carry a script in the extraction box. The save path must refuse one it
 * cannot run — an unterminated quote, or a last statement that returns no rows — before a
 * scheduled task repeats the failure at run time.
 */
class EtlTaskValidationScriptTest {

    private DatabaseConfigService configs;

    @BeforeEach
    void setUp() {
        configs = mock(DatabaseConfigService.class);
        when(configs.findById(1L)).thenReturn(Optional.of(config(1L)));
        when(configs.findById(2L)).thenReturn(Optional.of(config(2L)));
    }

    @Test
    void aMultiStatementScriptIsAccepted() {
        assertThatCode(() -> EtlTaskValidation.validateExtraction(
                task("CREATE TEMP TABLE t (id INT); SELECT id FROM t"), configs))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnterminatedQuoteIsRefusedBeforeTheTaskIsSaved() {
        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(
                task("SELECT 'abc"), configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.script.invalid");
    }

    @Test
    void aLastStatementThatReturnsNoRowsIsRefused() {
        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(
                task("CREATE TEMP TABLE t (id INT); COMMIT"), configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.script.lastStatement");
    }

    private static EtlTask task(String sourceSql) {
        EtlTask task = new EtlTask();
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setSourceSql(sourceSql);
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        return task;
    }

    private static DatabaseConfig config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.H2);
        return config;
    }
}
