package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * A CUSTOM connection's declared family also picks the metadata reader.
 *
 * <p>The generic JDBC reader is the fallback for unknown products, but it is also what stops a
 * custom Oracle-compatible database from getting column remarks, identity detection and the
 * other dictionary queries the product reader knows. With a declared family the cycle uses the
 * reader of a representative product from that family.
 */
class MetadataReaderFactoryCustomFamilyTest {

    private final MetadataReader postgres = mock(MetadataReader.class);
    private final MetadataReader oracle = mock(MetadataReader.class);
    private final MetadataReaderFactory factory =
            new MetadataReaderFactory(List.of(postgres, oracle));

    MetadataReaderFactoryCustomFamilyTest() {
        when(postgres.supports(DatabaseType.POSTGRESQL)).thenReturn(true);
        when(oracle.supports(DatabaseType.ORACLE)).thenReturn(true);
    }

    @Test
    void aCustomConnectionWithADeclaredFamilyGetsThatFamilysReader() {
        assertThat(factory.forType(DatabaseType.CUSTOM, DatabaseType.DialectFamily.POSTGRES))
                .isSameAs(postgres);
        assertThat(factory.forType(DatabaseType.CUSTOM, DatabaseType.DialectFamily.ORACLE))
                .isSameAs(oracle);
    }

    @Test
    void aCustomConnectionWithoutAFamilyKeepsTheGenericReader() {
        assertThat(factory.forType(DatabaseType.CUSTOM, null))
                .isInstanceOf(GenericMetadataReader.class);
        assertThat(factory.forType(DatabaseType.CUSTOM, DatabaseType.DialectFamily.GENERIC))
                .isInstanceOf(GenericMetadataReader.class);
    }

    @Test
    void aPresetIgnoresTheOverride() {
        // No MySQL reader is registered here; the override must not redirect the lookup.
        assertThat(factory.forType(DatabaseType.MYSQL, DatabaseType.DialectFamily.POSTGRES))
                .isInstanceOf(GenericMetadataReader.class);
    }
}
