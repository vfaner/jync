package com.qqmu.jync.service.migration.mongo;

import org.bson.Document;

/** A forward-only stream over a source collection's documents. */
public interface MongoDocumentCursor extends AutoCloseable {

    boolean hasNext();

    Document next();

    @Override
    void close();
}
