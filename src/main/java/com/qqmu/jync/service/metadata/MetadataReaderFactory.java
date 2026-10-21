package com.qqmu.jync.service.metadata;

import java.util.List;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

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
        return forType(type, null);
    }

    /**
     * Variant for a CUSTOM connection that declared the family it speaks. The generic JDBC
     * reader is only a fallback for unknown products; it does not know the dictionary queries
     * a real product family needs. The override applies to CUSTOM only.
     *
     * @param familyOverride the CUSTOM connection's declared family; null or GENERIC keeps
     *                       the generic reader
     */
    public MetadataReader forType(DatabaseType type, DatabaseType.DialectFamily familyOverride) {
        if (type == null) {
            return fallback;
        }
        if (type == DatabaseType.CUSTOM) {
            DatabaseType representative = representative(familyOverride);
            return representative == null ? fallback : forType(representative);
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

    /**
     * A preset that speaks the given family, used to look the reader up. Null for GENERIC,
     * whose only reader is the fallback itself.
     */
    private static DatabaseType representative(DatabaseType.DialectFamily family) {
        if (family == null) {
            return null;
        }
        switch (family) {
            case ORACLE:
                return DatabaseType.ORACLE;
            case MYSQL:
                return DatabaseType.MYSQL;
            case POSTGRES:
                return DatabaseType.POSTGRESQL;
            case SQLSERVER:
                return DatabaseType.SQLSERVER;
            case DB2:
                return DatabaseType.DB2;
            default:
                return null;
        }
    }
}
