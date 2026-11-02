package com.qqmu.jync.service.migration.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;

class MigrationConnectionAdapterRegistryTest {

    /** Stands in for a real adapter; only the declared kinds matter to the registry. */
    private static MigrationConnectionKindAdapter adapterFor(MigrationConnectionKind... kinds) {
        Set<MigrationConnectionKind> declared = Set.of(kinds);
        return new MigrationConnectionKindAdapter() {
            @Override
            public Set<MigrationConnectionKind> kinds() {
                return declared;
            }

            @Override
            public MigrationConnectionEndpoint normalize(MigrationConnectionForm form) {
                return new MigrationConnectionEndpoint(form.getBaseUrl(), null, null, null);
            }

            @Override
            public void validateAuth(MigrationConnectionForm form, String rawPassword,
                                     String rawApiKey, MigrationConnection existing) {
            }

            @Override
            public MigrationConnectionTestResult test(MigrationConnection connection) {
                return new MigrationConnectionTestResult(true, null, null, null, null, 0);
            }

            @Override
            public MigrationConnectionTestResult testTransient(MigrationConnection connection,
                                                               String plainPassword,
                                                               String plainApiKey) {
                return test(connection);
            }

            @Override
            public List<MigrationDiscoveryItem> list(MigrationConnection connection,
                                                     String pattern) {
                return List.of();
            }
        };
    }

    @Test
    void aKindDeclaredTwiceFailsStartup() {
        MigrationConnectionKindAdapter first =
                adapterFor(MigrationConnectionKind.ELASTICSEARCH);
        MigrationConnectionKindAdapter second =
                adapterFor(MigrationConnectionKind.ELASTICSEARCH);

        assertThatThrownBy(() -> new MigrationConnectionAdapterRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ELASTICSEARCH");
    }

    @Test
    void anUnknownKindIsRejectedWithAKey() {
        MigrationConnectionAdapterRegistry registry = new MigrationConnectionAdapterRegistry(
                List.of(adapterFor(MigrationConnectionKind.ELASTICSEARCH)));

        assertThatThrownBy(() -> registry.forKind(MigrationConnectionKind.MONGODB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.kind.unsupported");
    }

    @Test
    void aNullKindIsRejectedWithAKey() {
        MigrationConnectionAdapterRegistry registry = new MigrationConnectionAdapterRegistry(
                List.of(adapterFor(MigrationConnectionKind.ELASTICSEARCH)));

        assertThatThrownBy(() -> registry.forKind(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.kind.required");
    }

    @Test
    void theSearchKindsAllResolveToTheSameAdapter() {
        MigrationConnectionKindAdapter search = adapterFor(
                MigrationConnectionKind.ELASTICSEARCH,
                MigrationConnectionKind.OPENSEARCH,
                MigrationConnectionKind.EASYSEARCH);
        MigrationConnectionAdapterRegistry registry =
                new MigrationConnectionAdapterRegistry(List.of(search));

        assertThat(registry.forKind(MigrationConnectionKind.ELASTICSEARCH)).isSameAs(search);
        assertThat(registry.forKind(MigrationConnectionKind.OPENSEARCH)).isSameAs(search);
        assertThat(registry.forKind(MigrationConnectionKind.EASYSEARCH)).isSameAs(search);
        assertThat(registry.kinds()).containsExactlyInAnyOrder(
                MigrationConnectionKind.ELASTICSEARCH,
                MigrationConnectionKind.OPENSEARCH,
                MigrationConnectionKind.EASYSEARCH);
    }
}
