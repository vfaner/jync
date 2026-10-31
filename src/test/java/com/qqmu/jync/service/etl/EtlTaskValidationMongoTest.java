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
 * A native Mongo source has no extraction SQL: the collection and the optional filter replace
 * it. Validation must branch on the source preset, or every Mongo task would be refused for
 * the SQL it deliberately does not have.
 */
class EtlTaskValidationMongoTest {

    private DatabaseConfigService configs;

    @BeforeEach
    void setUp() {
        configs = mock(DatabaseConfigService.class);
    }

    @Test
    void aNativeSourceNeedsACollectionEvenWithoutSql() {
        stub(mongoConfig(1L), h2Config(2L));

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(
                task(" "), configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.collection.required");
    }

    @Test
    void aCollectionCannotUseTheReservedDollarPrefix() {
        stub(mongoConfig(1L), h2Config(2L));

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(
                task("$cmd"), configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.collection.invalid");
    }

    @Test
    void aValidCollectionReplacesTheRequiredSql() {
        stub(mongoConfig(1L), h2Config(2L));

        assertThatCode(() -> EtlTaskValidation.validateExtraction(task("orders"), configs))
                .doesNotThrowAnyException();
    }

    @Test
    void anEmptyOrMissingFilterIsAccepted() {
        stub(mongoConfig(1L), h2Config(2L));
        EtlTask task = task("orders");
        task.setSourceFilter("{ \"status\": \"active\" }");

        assertThatCode(() -> EtlTaskValidation.validateExtraction(task, configs))
                .doesNotThrowAnyException();
    }

    @Test
    void aMalformedFilterIsRefused() {
        stub(mongoConfig(1L), h2Config(2L));
        EtlTask task = task("orders");
        task.setSourceFilter("{\"status\":");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.filter.invalid");
    }

    @Test
    void aFilterThatRunsCodeIsRefused() {
        stub(mongoConfig(1L), h2Config(2L));
        EtlTask task = task("orders");
        task.setSourceFilter("{\"$where\":\"true\"}");

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.filter.blocked");
    }

    @Test
    void aNativeSourceCanNeverBeTheTarget() {
        stub(h2Config(1L), mongoConfig(2L));

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task("orders"), configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.target.readOnly");
    }

    @Test
    void aJdbcSourceStillNeedsItsSql() {
        stub(h2Config(1L), h2Config(2L));
        EtlTask task = task("orders");
        task.setSourceSql(" ");
        task.setSourceDbId(1L);

        assertThatThrownBy(() -> EtlTaskValidation.validateExtraction(task, configs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.sql.required");
    }

    // --- helpers ------------------------------------------------------------------------

    private void stub(DatabaseConfig source, DatabaseConfig target) {
        when(configs.findById(source.getId())).thenReturn(Optional.of(source));
        when(configs.findById(target.getId())).thenReturn(Optional.of(target));
    }

    private static EtlTask task(String collection) {
        EtlTask task = new EtlTask();
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setSourceSql("");
        task.setSourceCollection(collection);
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        return task;
    }

    private static DatabaseConfig mongoConfig(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.MONGODB_NATIVE);
        return config;
    }

    private static DatabaseConfig h2Config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.H2);
        return config;
    }
}
