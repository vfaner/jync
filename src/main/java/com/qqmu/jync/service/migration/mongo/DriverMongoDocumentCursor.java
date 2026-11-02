package com.qqmu.jync.service.migration.mongo;

import org.bson.Document;

import com.mongodb.client.MongoCursor;

/** Adapts the driver's cursor to the small interface the engine uses. */
final class DriverMongoDocumentCursor implements MongoDocumentCursor {

    private final MongoCursor<Document> cursor;

    DriverMongoDocumentCursor(MongoCursor<Document> cursor) {
        this.cursor = cursor;
    }

    @Override
    public boolean hasNext() {
        return cursor.hasNext();
    }

    @Override
    public Document next() {
        return cursor.next();
    }

    @Override
    public void close() {
        cursor.close();
    }
}
