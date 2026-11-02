package com.qqmu.jync.service.migration.mongo;

import java.util.List;

import org.bson.Document;

/**
 * The narrow surface a MongoDB → MongoDB run needs. Keeping it behind an interface lets the
 * engine's branching (write modes, naming, verification) be tested without a server.
 */
public interface MongoMigrationGateway extends AutoCloseable {

    void ping();

    /** Every entry of {@code listCollections}, including views and time-series collections. */
    List<MongoCollectionInfo> listCollections();

    boolean collectionExists(String name);

    Document collectionOptions(String name);

    /** Raw {@code listIndexes} entries, including the {@code _id_} index. */
    List<Document> listIndexes(String name);

    void createCollection(String name, Document options);

    void dropCollection(String name);

    void createIndex(String collection, Document spec);

    long countDocuments(String collection);

    List<Document> sample(String collection, int limit);

    MongoDocumentCursor find(String collection, int batchSize, boolean noCursorTimeout);

    BulkInsertResult insertMany(String collection, List<Document> documents, boolean ordered);

    List<Document> findByIds(String collection, List<Object> ids);

    @Override
    void close();
}
