package com.qqmu.jync.service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;

class MigrationTypeRegistryTest {

    private static MigrationTypeHandler handler(MigrationType type) {
        return new MigrationTypeHandler() {
            @Override
            public MigrationType type() {
                return type;
            }

            @Override
            public Set<MigrationConnectionKind> sourceKinds() {
                return Set.of(MigrationConnectionKind.ELASTICSEARCH);
            }

            @Override
            public Set<MigrationConnectionKind> targetKinds() {
                return Set.of(MigrationConnectionKind.EASYSEARCH);
            }

            @Override
            public String optionsFragment() {
                return "~{migration-form/options-es :: options}";
            }

            @Override
            public JsonNode defaultOptions() {
                return JsonNodeFactory.instance.objectNode();
            }

            @Override
            public JsonNode parseOptions(ObjectNode formOptions) {
                return formOptions;
            }

            @Override
            public MigrationPrecheckResult precheck(MigrationTask task, JsonNode options,
                                                    MigrationConnection source,
                                                    MigrationConnection target) {
                return new MigrationPrecheckResult(List.of());
            }

            @Override
            public MigrationRunResult run(MigrationTask task, JsonNode options,
                                          MigrationConnection source,
                                          MigrationConnection target,
                                          MigrationCancellation cancellation,
                                          MigrationProgressListener progress) {
                return MigrationRunResult.empty();
            }
        };
    }

    @Test
    void aRegisteredTypeIsFound() {
        MigrationTypeRegistry registry = new MigrationTypeRegistry(
                List.of(handler(MigrationType.ES_TO_EASYSEARCH)));

        assertThat(registry.find(MigrationType.ES_TO_EASYSEARCH)).isPresent();
        assertThat(registry.find(MigrationType.MONGODB_TO_MONGODB)).isEmpty();
    }

    @Test
    void duplicateHandlersForOneTypeAreRejectedAtStartup() {
        assertThatThrownBy(() -> new MigrationTypeRegistry(List.of(
                handler(MigrationType.ES_TO_EASYSEARCH),
                handler(MigrationType.ES_TO_EASYSEARCH))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void aHandlerForAnUnavailableTypeIsRejectedAtStartup() {
        assertThatThrownBy(() -> new MigrationTypeRegistry(
                List.of(handler(MigrationType.MONGODB_TO_MONGODB))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available");
    }
}
