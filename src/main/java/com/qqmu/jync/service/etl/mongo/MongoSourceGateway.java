package com.qqmu.jync.service.etl.mongo;

import java.util.List;

import org.bson.Document;

import com.qqmu.jync.model.DatabaseConfig;

/**
 * The subset of the MongoDB driver an ETL source uses. The configuration is passed per call
 * rather than held so a test can verify the sample and the query against the same connection
 * object the factory opened.
 */
public interface MongoSourceGateway extends AutoCloseable {

    boolean collectionExists(DatabaseConfig config, String collection);

    /**
     * Up to {@code limit} documents in {@code _id} order. The sample decides the target
     * columns, so it is taken with the task's own filter and timeout.
     *
     * @param timeoutSeconds {@code > 0} bounds the read; {@code 0} leaves it unbounded
     */
    List<Document> sample(DatabaseConfig config, String collection, Document filter,
                          int limit, int timeoutSeconds);

    /**
     * @param sort           {@code null} for an overwrite run; {@code {key: 1}} when resuming
     * @param timeoutSeconds {@code > 0} bounds the query; {@code 0} leaves it unbounded
     */
    MongoCursorHandle find(DatabaseConfig config, String collection, Document filter,
                           Document sort, int batchSize, int timeoutSeconds);

    @Override
    void close();
}
