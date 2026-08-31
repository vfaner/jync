package com.synctool.service.metadata;

import java.util.List;

import org.springframework.stereotype.Component;

import com.synctool.model.DatabaseType;

/**
 * Picks the {@link MetadataReader} for a database type, falling back to the JDBC-only
 * generic reader for unrecognized and custom products.
 */
@Component
public class MetadataReaderFactory {

    private final List<MetadataReader> readers;
    private final GenericMetadataReader fallback = new GenericMetadataReader();

    public MetadataReaderFactory(List<MetadataReader> readers) {
        this.readers = readers;
    }

    public MetadataReader forType(DatabaseType type) {
        if (type == null) {
            return fallback;
        }
        for (MetadataReader reader : readers) {
            // Skip the catch-all so a specific reader always wins.
            if (reader instanceof GenericMetadataReader
                    && reader.getClass() == GenericMetadataReader.class) {
                continue;
            }
            if (reader.supports(type)) {
                return reader;
            }
        }
        return fallback;
    }
}
