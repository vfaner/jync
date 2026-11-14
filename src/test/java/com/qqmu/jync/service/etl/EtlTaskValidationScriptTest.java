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

    @Test
    void aResumableSqlServerCteIsRefused() {
        sourceType(DatabaseType.SQLSERVER);
        EtlTask task = task("WITH c AS (SELECT id FROM src) SELECT id FROM c");
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setResumeKeyColumn("id");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.resume.cte");
    }

    @Test
    void aResumableDb2CteIsRefused() {
        sourceType(DatabaseType.DB2);
        EtlTask task = task("WITH c AS (SELECT id FROM src) SELECT id FROM c");
        task.setWriteMode(EtlWriteMode.RANGE_OVERWRITE);
        task.setResumeKeyColumn("id");
        task.setRangeCondition("id > 0");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.resume.cte");
    }

    /**
     * SQL Server 只在派生表里禁止裸的 ORDER BY;带 TOP / OFFSET / FOR XML 时它是合法语法
     * (错误 1033 的反面)。这类语句以前能续跑,校验不能把它们挡在门外;运行期的包装也
     * 原样保留 ORDER BY,因为去掉它会改变 TOP 选中的行。
     */
    @Test
    void aResumableSqlServerTopWithOrderByIsAccepted() {
        sourceType(DatabaseType.SQLSERVER);
        EtlTask task = task("SELECT TOP 10 id FROM src ORDER BY id");
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setResumeKeyColumn("id");

        assertThatCode(() -> EtlTaskValidation.validateExtraction(task, configs))
                .doesNotThrowAnyException();
    }

    /** DB2 同样的规则:子查询里的 ORDER BY 在带 OFFSET / FETCH FIRST 时合法。 */
    @Test
    void aResumableDb2OrderByWithFetchIsAccepted() {
        sourceType(DatabaseType.DB2);
        EtlTask task = task("SELECT id FROM src ORDER BY id OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY");
        task.setWriteMode(EtlWriteMode.RANGE_OVERWRITE);
        task.setResumeKeyColumn("id");
        task.setRangeCondition("id > 0");

        assertThatCode(() -> EtlTaskValidation.validateExtraction(task, configs))
                .doesNotThrowAnyException();
    }

    @Test
    void aResumableSqlServerRedundantOrderByIsAcceptedAndDroppedAtRunTime() {
        sourceType(DatabaseType.SQLSERVER);
        EtlTask task = task("SELECT id FROM src ORDER BY created_at");
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setResumeKeyColumn("id");

        assertThatCode(() -> EtlTaskValidation.validateExtraction(task, configs))
                .doesNotThrowAnyException();
    }

    @Test
    void aNonResumableSqlServerCteIsAccepted() {
        sourceType(DatabaseType.SQLSERVER);

        assertThatCode(() -> EtlTaskValidation.validateExtraction(
                task("WITH c AS (SELECT id FROM src) SELECT id FROM c"), configs))
                .doesNotThrowAnyException();
    }

    private void sourceType(DatabaseType type) {
        when(configs.findById(1L)).thenReturn(Optional.of(config(1L, type)));
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
        return config(id, DatabaseType.H2);
    }

    private static DatabaseConfig config(Long id, DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(type);
        return config;
    }
}
