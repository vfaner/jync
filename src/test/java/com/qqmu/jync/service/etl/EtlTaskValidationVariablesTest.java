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
 * Placeholders are checked when the task is saved and when it is prechecked, before the
 * operator leaves the form. The Mongo collection and filter are not SQL and are never scanned.
 */
class EtlTaskValidationVariablesTest {

    private DatabaseConfigService configs;

    @BeforeEach
    void setUp() {
        configs = mock(DatabaseConfigService.class);
        when(configs.findById(1L)).thenReturn(Optional.of(config(1L, DatabaseType.H2)));
        when(configs.findById(2L)).thenReturn(Optional.of(config(2L, DatabaseType.H2)));
    }

    @Test
    void aDefinedVariableIsAccepted() {
        EtlTask task = task("SELECT * FROM src WHERE tenant = ${tenant}");
        task.setVariables("tenant='acme'");

        assertThatCode(() -> EtlTaskValidation.validateExtraction(task, configs))
                .doesNotThrowAnyException();
    }

    @Test
    void aBuiltInNeedsNoDefinition() {
        assertThatCode(() -> EtlTaskValidation.validateExtraction(
                task("SELECT * FROM src WHERE dt = '${date}'"), configs))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnknownVariableInTheExtractionSqlIsRefused() {
        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(
                task("SELECT * FROM src WHERE dt = '${nope}'"), configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.var.unknown:nope");
    }

    @Test
    void anUnknownVariableInThePreSqlIsRefused() {
        EtlTask task = task("SELECT id FROM src");
        task.setPreSql("DELETE FROM dst WHERE dt = '${nope}'");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.var.unknown:nope");
    }

    @Test
    void anUnknownVariableInThePostSqlIsRefused() {
        EtlTask task = task("SELECT id FROM src");
        task.setPostSql("UPDATE dst SET x = ${nope}");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.var.unknown:nope");
    }

    @Test
    void anUnknownVariableInTheRangeConditionIsRefused() {
        EtlTask task = task("SELECT id FROM src");
        task.setWriteMode(EtlWriteMode.RANGE_OVERWRITE);
        task.setRangeCondition("dt = '${nope}'");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.var.unknown:nope");
    }

    @Test
    void anInvalidDefinitionIsRefusedEvenWithoutAReference() {
        EtlTask task = task("SELECT id FROM src");
        task.setVariables("a=1\na=2");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.var.duplicate:a");
    }

    @Test
    void aTdengineTargetRefusesUpsertBeforeAskingForKeys() {
        when(configs.findById(2L)).thenReturn(Optional.of(config(2L, DatabaseType.TDENGINE)));
        EtlTask task = task("SELECT id FROM src");
        task.setWriteMode(EtlWriteMode.UPSERT);
        task.setUpsertKeyColumns("");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.tdengine.upsert");
    }

    @Test
    void aTdengineTargetStillAcceptsTheModesItCanWrite() {
        when(configs.findById(2L)).thenReturn(Optional.of(config(2L, DatabaseType.TDENGINE)));

        assertThatCode(() -> EtlTaskValidation.validateExtraction(
                task("SELECT id FROM src"), configs))
                .doesNotThrowAnyException();
    }

    @Test
    void aMongoExtractionIsNotScannedButItsPreSqlIs() {
        when(configs.findById(1L)).thenReturn(Optional.of(config(1L, DatabaseType.MONGODB_NATIVE)));
        EtlTask mongo = mongoTask();
        mongo.setSourceSql("not sql ${nope}");

        assertThatCode(() -> EtlTaskValidation.validateExtraction(mongo, configs))
                .doesNotThrowAnyException();

        mongo.setPreSql("DELETE FROM '${nope}'");
        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(mongo, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.var.unknown:nope");
    }

    @Test
    void aMongoCollectionAndFilterAreNotScanned() {
        when(configs.findById(1L)).thenReturn(Optional.of(config(1L, DatabaseType.MONGODB_NATIVE)));
        EtlTask mongo = mongoTask();
        mongo.setSourceCollection("orders_${nope}");
        mongo.setSourceFilter("{\"tenant\": \"${nope}\"}");

        assertThatCode(() -> EtlTaskValidation.validateExtraction(mongo, configs))
                .doesNotThrowAnyException();
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

    private static EtlTask mongoTask() {
        EtlTask task = task("");
        task.setSourceCollection("orders");
        return task;
    }

    private static DatabaseConfig config(Long id, DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(type);
        return config;
    }
}
