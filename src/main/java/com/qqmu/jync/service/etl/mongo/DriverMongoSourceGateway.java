package com.qqmu.jync.service.etl.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCursor;
import com.qqmu.jync.model.DatabaseConfig;

/**
 * The native-driver implementation of {@link MongoSourceGateway}. Every read is bounded by the
 * caller's sample limit, batch size and timeout; nothing here writes.
 */
public final class DriverMongoSourceGateway implements MongoSourceGateway {

    private final MongoClient client;
    private final String database;

    public DriverMongoSourceGateway(MongoClient client, String database) {
        this.client = client;
        this.database = database;
    }

    @Override
    public boolean collectionExists(DatabaseConfig config, String collection) {
        return client.getDatabase(database).listCollectionNames()
                .into(new ArrayList<>()).contains(collection);
    }

    @Override
    public List<Document> sample(DatabaseConfig config, String collection, Document filter,
                                 int limit, int timeoutSeconds) {
        FindIterable<Document> iterable = client.getDatabase(database).getCollection(collection)
                .find(filter)
                .sort(new Document("_id", 1))
                .limit(limit)
                .batchSize(limit);
        return bounded(iterable, timeoutSeconds).into(new ArrayList<>());
    }

    @Override
    public MongoCursorHandle find(DatabaseConfig config, String collection, Document filter,
                                  Document sort, int batchSize, int timeoutSeconds) {
        FindIterable<Document> iterable = client.getDatabase(database).getCollection(collection)
                .find(filter)
                .batchSize(batchSize);
        if (sort != null) {
            // A resume run sorts by the key; a large collection can exceed the in-memory sort
            // budget, and spilling to disk is exactly what a bounded server-side sort wants.
            iterable = iterable.sort(sort).allowDiskUse(true);
        }
        return new DriverCursor(bounded(iterable, timeoutSeconds).iterator());
    }

    @Override
    public void close() {
        client.close();
    }

    private static FindIterable<Document> bounded(FindIterable<Document> iterable,
                                                  int timeoutSeconds) {
        return timeoutSeconds > 0
                ? iterable.maxTime(timeoutSeconds, TimeUnit.SECONDS) : iterable;
    }

    private static final class DriverCursor implements MongoCursorHandle {

        private final MongoCursor<Document> cursor;
        private Document current;

        private DriverCursor(MongoCursor<Document> cursor) {
            this.cursor = cursor;
        }

        @Override
        public boolean next() {
            if (!cursor.hasNext()) {
                current = null;
                return false;
            }
            current = cursor.next();
            return true;
        }

        @Override
        public Document current() {
            return current;
        }

        @Override
        public void close() {
            cursor.close();
        }
    }
}
