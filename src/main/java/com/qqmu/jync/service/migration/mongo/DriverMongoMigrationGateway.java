package com.qqmu.jync.service.migration.mongo;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;

/**
 * The native-driver implementation. Commands that carry a raw spec ({@code create},
 * {@code createIndexes}) are sent as commands so the caller's document reaches the server
 * unchanged, apart from the read-only fields already dropped upstream.
 */
final class DriverMongoMigrationGateway implements MongoMigrationGateway {

    private static final int DUPLICATE_KEY = 11000;
    private static final int DUPLICATE_KEY_LEGACY = 11001;

    private final com.mongodb.client.MongoClient client;
    private final MongoDatabase database;

    DriverMongoMigrationGateway(com.mongodb.client.MongoClient client, MongoDatabase database) {
        this.client = client;
        this.database = database;
    }

    @Override
    public void ping() {
        database.runCommand(new Document("ping", 1));
    }

    @Override
    public List<MongoCollectionInfo> listCollections() {
        List<MongoCollectionInfo> result = new ArrayList<>();
        for (Document entry : database.listCollections().into(new ArrayList<>())) {
            result.add(new MongoCollectionInfo(entry.getString("name"), entry.getString("type")));
        }
        return result;
    }

    @Override
    public boolean collectionExists(String name) {
        return describeCollection(name) != null;
    }

    @Override
    public Document collectionOptions(String name) {
        Document entry = describeCollection(name);
        Document options = entry == null ? null : entry.get("options", Document.class);
        return options == null ? new Document() : options;
    }

    @Override
    public List<Document> listIndexes(String name) {
        return database.getCollection(name).listIndexes().into(new ArrayList<>());
    }

    @Override
    public void createCollection(String name, Document options) {
        Document command = new Document("create", name);
        MongoCollectionSupport.cleanOptions(options).forEach(command::append);
        database.runCommand(command);
    }

    @Override
    public void dropCollection(String name) {
        database.getCollection(name).drop();
    }

    @Override
    public void createIndex(String collection, Document spec) {
        database.runCommand(new Document("createIndexes", collection)
                .append("indexes", List.of(MongoCollectionSupport.cleanIndex(spec))));
    }

    @Override
    public long countDocuments(String collection) {
        return database.getCollection(collection).countDocuments();
    }

    @Override
    public List<Document> sample(String collection, int limit) {
        return database.getCollection(collection).find().limit(Math.max(limit, 1))
                .into(new ArrayList<>());
    }

    @Override
    public MongoDocumentCursor find(String collection, int batchSize, boolean noCursorTimeout) {
        FindIterable<Document> iterable = database.getCollection(collection).find();
        if (batchSize > 0) {
            iterable = iterable.batchSize(batchSize);
        }
        if (noCursorTimeout) {
            iterable = iterable.noCursorTimeout(true);
        }
        return new DriverMongoDocumentCursor(iterable.iterator());
    }

    @Override
    public BulkInsertResult insertMany(String collection, List<Document> documents,
                                       boolean ordered) {
        if (documents.isEmpty()) {
            return new BulkInsertResult(0, 0);
        }
        try {
            int inserted = database.getCollection(collection)
                    .insertMany(documents, new InsertManyOptions().ordered(ordered))
                    .getInsertedIds().size();
            return new BulkInsertResult(inserted, 0);
        } catch (MongoBulkWriteException e) {
            if (e.getWriteConcernError() != null) {
                // A write-concern failure means the server may not have durably accepted the
                // batch; reporting its duplicate errors as "skipped" would claim success.
                throw e;
            }
            int duplicates = 0;
            for (BulkWriteError error : e.getWriteErrors()) {
                if (!isDuplicateKey(error)) {
                    throw e;
                }
                duplicates++;
            }
            return new BulkInsertResult(e.getWriteResult().getInsertedCount(), duplicates);
        }
    }

    @Override
    public List<Document> findByIds(String collection, List<Object> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return database.getCollection(collection)
                .find(new Document("_id", new Document("$in", ids)))
                .into(new ArrayList<>());
    }

    @Override
    public void close() {
        client.close();
    }

    static boolean isDuplicateKey(BulkWriteError error) {
        return error.getCode() == DUPLICATE_KEY || error.getCode() == DUPLICATE_KEY_LEGACY;
    }

    private Document describeCollection(String name) {
        Document response = database.runCommand(new Document("listCollections", 1)
                .append("filter", new Document("name", name)));
        Document cursor = response.get("cursor", Document.class);
        if (cursor == null) {
            return null;
        }
        List<Document> firstBatch = cursor.getList("firstBatch", Document.class);
        return firstBatch == null || firstBatch.isEmpty() ? null : firstBatch.get(0);
    }
}
