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
     * 带 TOP 的语句在派生表里语法合法,但续传语义是错的:续传包装把
     * {@code WHERE key > ?} 加在派生表外面,内层 TOP 在过滤之前生效。第一次之后每轮都
     * 重新读回同一批 Top N 行、被全部过滤掉,剩余行永远复制不到,任务却一直报成功。
     * 语法合法不等于可以续跑,校验要在保存时就拒绝。
     */
    @Test
    void aResumableSqlServerTopIsRefused() {
        sourceType(DatabaseType.SQLSERVER);
        EtlTask task = task("SELECT TOP 10 id FROM src ORDER BY id");
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setResumeKeyColumn("id");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.resume.limit");
    }

    /** DB2 的 OFFSET / FETCH NEXT 同样把窗口冻结在第一批。 */
    @Test
    void aResumableDb2FetchIsRefused() {
        sourceType(DatabaseType.DB2);
        EtlTask task = task("SELECT id FROM src ORDER BY id OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY");
        task.setWriteMode(EtlWriteMode.RANGE_OVERWRITE);
        task.setResumeKeyColumn("id");
        task.setRangeCondition("id > 0");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.resume.limit");
    }

    /** 不续传时行数限制没有这个问题,必须继续放行。 */
    @Test
    void aNonResumableTopIsStillAccepted() {
        sourceType(DatabaseType.SQLSERVER);
        EtlTask task = task("SELECT TOP 10 id FROM src ORDER BY id");
        task.setWriteMode(EtlWriteMode.OVERWRITE);

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

    /**
     * 保存校验必须按源库自己的词法分句:PostgreSQL 的 'C:\' 在反斜杠后就已闭合,后面的分号
     * 是真分句点;沿用 MySQL「反斜杠转义引号」的读法会把整段判成未闭合字符串并拒绝保存。
     */
    @Test
    void aPostgresBackslashBeforeAQuoteDoesNotSwallowTheNextStatement() {
        sourceType(DatabaseType.POSTGRESQL);

        assertThatCode(() -> EtlTaskValidation.validateExtraction(
                task("SELECT 'C:\\'; SELECT 2"), configs))
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
