package com.qqmu.jync.service.etl.mongo;

import org.bson.Document;

/**
 * A driver cursor reduced to the two calls the ETL source needs. Keeping the driver's
 * {@code MongoCursor} behind this interface lets the source tests assert the query that was
 * built without a live server.
 */
public interface MongoCursorHandle extends AutoCloseable {

    boolean next();

    /** The document returned by the last successful {@link #next()}. */
    Document current();

    @Override
    void close();
}
