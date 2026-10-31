package com.qqmu.jync.service.etl;

import java.util.List;

/**
 * A table selection the batch cannot be built from. The offending names travel with the error so
 * the form can say which tables were unknown, already present, or unusable as identifiers.
 */
public class EtlBatchTableException extends IllegalArgumentException {

    private final List<String> names;

    public EtlBatchTableException(String messageKey, List<String> names) {
        super(messageKey);
        this.names = List.copyOf(names);
    }

    public List<String> names() {
        return names;
    }
}
