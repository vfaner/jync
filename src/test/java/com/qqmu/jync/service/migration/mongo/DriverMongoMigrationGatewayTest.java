package com.qqmu.jync.service.migration.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.bulk.WriteConcernError;
import com.mongodb.client.ListCollectionsIterable;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.result.InsertManyResult;

@SuppressWarnings("unchecked")
class DriverMongoMigrationGatewayTest {

    private MongoClient client;
    private MongoDatabase database;
    private DriverMongoMigrationGateway gateway;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        database = mock(MongoDatabase.class);
        gateway = new DriverMongoMigrationGateway(client, database);
    }

    @Test
    void createCollectionSendsTheSourceOptionsWithoutReadOnlyFields() {
        gateway.createCollection("orders", new Document("uuid", "abc")
                .append("name", "orders")
                .append("type", "collection")
                .append("capped", true)
                .append("size", 1048576L));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(database).runCommand(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("create", "orders")
                .containsEntry("capped", true)
                .containsEntry("size", 1048576L)
                .doesNotContainKeys("uuid", "name", "type");
    }

    @Test
    void createIndexSendsTheSpecWithoutReadOnlyFields() {
        gateway.createIndex("orders", new Document("name", "email_1")
                .append("key", new Document("email", 1))
                .append("v", 2)
                .append("ns", "source.orders")
                .append("background", true)
                .append("unique", true));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(database).runCommand(captor.capture());
        Document command = captor.getValue();
        assertThat(command).containsEntry("createIndexes", "orders");
        List<Document> indexes = command.getList("indexes", Document.class);
        assertThat(indexes).singleElement().satisfies(index -> assertThat(index)
                .containsEntry("name", "email_1")
                .containsEntry("unique", true)
                .containsKey("key")
                .doesNotContainKeys("v", "ns", "background"));
    }

    @Test
    void insertManyReportsDuplicateKeysInsteadOfFailingTheBatch() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("orders")).thenReturn(collection);
        List<Document> documents = List.of(new Document("_id", 1), new Document("_id", 2));
        MongoBulkWriteException failure = duplicateKeyFailure(1);
        when(collection.insertMany(eq(documents), any(InsertManyOptions.class)))
                .thenThrow(failure);

        BulkInsertResult result = gateway.insertMany("orders", documents, false);

        assertThat(result).isEqualTo(new BulkInsertResult(1, 1));
    }

    @Test
    void insertManyRethrowsWriteErrorsThatAreNotDuplicates() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("orders")).thenReturn(collection);
        List<Document> documents = List.of(new Document("_id", 1));
        MongoBulkWriteException failure = bulkFailure(121, 0);
        when(collection.insertMany(eq(documents), any(InsertManyOptions.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> gateway.insertMany("orders", documents, false))
                .isSameAs(failure);
    }

    @Test
    void insertManyRethrowsAWriteConcernErrorEvenWhenEveryWriteErrorIsADuplicate() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("orders")).thenReturn(collection);
        List<Document> documents = List.of(new Document("_id", 1), new Document("_id", 2));
        MongoBulkWriteException failure = bulkFailure(11000, 1,
                new WriteConcernError(64, "WriteConcernFailed",
                        "waiting for replication timed out", new BsonDocument()));
        when(collection.insertMany(eq(documents), any(InsertManyOptions.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> gateway.insertMany("orders", documents, false))
                .isSameAs(failure);
    }

    @Test
    void listCollectionsKeepsTheEntryType() {
        ListCollectionsIterable<Document> iterable = mock(ListCollectionsIterable.class);
        when(database.listCollections()).thenReturn(iterable);
        when(iterable.into(ArgumentMatchers.<List<Document>>any())).thenReturn(
                new java.util.ArrayList<>(List.of(
                        new Document("name", "orders"),
                        new Document("name", "orders_view").append("type", "view"))));

        List<MongoCollectionInfo> collections = gateway.listCollections();

        assertThat(collections).containsExactly(
                new MongoCollectionInfo("orders", null),
                new MongoCollectionInfo("orders_view", "view"));
        assertThat(collections.get(0).isCollection()).isTrue();
        assertThat(collections.get(1).isCollection()).isFalse();
    }

    @Test
    void collectionOptionsAndExistenceComeFromListCollections() {
        Document response = new Document("cursor", new Document("firstBatch", List.of(
                new Document("name", "orders")
                        .append("options", new Document("capped", true)))));
        when(database.runCommand(any(Document.class))).thenReturn(response);

        assertThat(gateway.collectionExists("orders")).isTrue();
        assertThat(gateway.collectionOptions("orders")).containsEntry("capped", true);

        when(database.runCommand(any(Document.class))).thenReturn(
                new Document("cursor", new Document("firstBatch", List.of())));
        assertThat(gateway.collectionExists("missing")).isFalse();
        assertThat(gateway.collectionOptions("missing")).isEmpty();
    }

    @Test
    void listIndexesReturnsEveryEntryIncludingTheIdIndex() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("orders")).thenReturn(collection);
        ListIndexesIterable<Document> iterable = mock(ListIndexesIterable.class);
        when(collection.listIndexes()).thenReturn(iterable);
        List<Document> indexes = List.of(new Document("name", "_id_"),
                new Document("name", "email_1"));
        when(iterable.into(ArgumentMatchers.<List<Document>>any())).thenReturn(indexes);

        assertThat(gateway.listIndexes("orders")).containsExactlyElementsOf(indexes);
    }

    @Test
    void orderedInsertRetriesAfterADuplicateSoTheRestOfTheBatchIsNotDropped() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("orders")).thenReturn(collection);
        List<Document> documents = List.of(
                new Document("_id", 1), new Document("_id", 2), new Document("_id", 3));
        MongoBulkWriteException failure = bulkFailure(11000, 1, 1);
        when(collection.insertMany(eq(documents), any(InsertManyOptions.class)))
                .thenThrow(failure);
        List<Document> remainder = List.of(documents.get(2));
        when(collection.insertMany(eq(remainder), any(InsertManyOptions.class)))
                .thenReturn(InsertManyResult.acknowledged(Map.of(0, new BsonInt32(3))));

        BulkInsertResult result = gateway.insertMany("orders", documents, true);

        assertThat(result).isEqualTo(new BulkInsertResult(2, 1));
        ArgumentCaptor<InsertManyOptions> options = ArgumentCaptor.forClass(InsertManyOptions.class);
        verify(collection, times(2))
                .insertMany(ArgumentMatchers.<List<Document>>any(), options.capture());
        assertThat(options.getAllValues())
                .allSatisfy(option -> assertThat(option.isOrdered()).isTrue());
    }

    private static MongoBulkWriteException duplicateKeyFailure(int inserted) {
        return bulkFailure(11000, inserted);
    }

    private static MongoBulkWriteException bulkFailure(int code, int inserted) {
        return bulkFailure(code, inserted, null);
    }

    private static MongoBulkWriteException bulkFailure(int code, int inserted,
                                                       WriteConcernError concern) {
        return bulkFailure(code, inserted, 0, concern);
    }

    private static MongoBulkWriteException bulkFailure(int code, int inserted, int index) {
        return bulkFailure(code, inserted, index, null);
    }

    private static MongoBulkWriteException bulkFailure(int code, int inserted, int index,
                                                       WriteConcernError concern) {
        BulkWriteResult writeResult = mock(BulkWriteResult.class);
        when(writeResult.getInsertedCount()).thenReturn(inserted);
        BulkWriteError error = new BulkWriteError(code, "write error", new BsonDocument(), index);
        return new MongoBulkWriteException(writeResult, List.of(error), concern,
                new ServerAddress());
    }
}
