package com.qqmu.jync.service.migration.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.service.migration.MigrationCancellation;
import com.qqmu.jync.service.migration.MigrationProgressListener;
import com.qqmu.jync.service.migration.MigrationRunResult;
import com.qqmu.jync.service.migration.MigrationRunResult.IndexResult;
import com.qqmu.jync.service.migration.MigrationRunResult.Status;

@ExtendWith(MockitoExtension.class)
class MongoToMongoEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private MongoMigrationGatewayFactory gatewayFactory;
    @Mock private MongoMigrationGateway sourceGateway;
    @Mock private MongoMigrationGateway targetGateway;

    private MongoToMongoEngine engine;
    private MigrationConnection source;
    private MigrationConnection target;
    private MigrationTask task;

    @BeforeEach
    void setUp() {
        engine = new MongoToMongoEngine(gatewayFactory);
        source = connection(1L, "source");
        target = connection(2L, "target");
        task = new MigrationTask();
        task.setId(9L);
        task.setType(MigrationType.MONGODB_TO_MONGODB);
        lenient().when(gatewayFactory.open(source)).thenReturn(sourceGateway);
        lenient().when(gatewayFactory.open(target)).thenReturn(targetGateway);
    }

    @Test
    void documentsAreCopiedUnchangedAndKeepTheirId() {
        Document first = new Document("_id", new ObjectId("64b7f0c2a1b2c3d4e5f60718"))
                .append("qty", 2);
        Document second = new Document("_id", new ObjectId("64b7f0c2a1b2c3d4e5f60719"))
                .append("qty", 3);
        stubOneCollection(List.of(first, second));

        MigrationRunResult result = engine.run(task, options("{}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isTrue();
        assertThat(result.documentsRead()).isEqualTo(2);
        assertThat(result.documentsWritten()).isEqualTo(2);
        assertThat(result.indices()).singleElement()
                .extracting(IndexResult::status).isEqualTo(Status.SUCCESS);

        ArgumentCaptor<List<Document>> captor = listCaptor();
        verify(targetGateway).insertMany(eq("orders"), captor.capture(), eq(false));
        assertThat(captor.getValue()).containsExactly(first, second);
        verify(targetGateway).createCollection(eq("orders"), any());
    }

    @Test
    void pointingSourceAndTargetAtTheSameDatabaseIsRefusedBeforeAnythingIsCopied() {
        MigrationConnection sameDatabase = connection(3L, "source");
        lenient().when(gatewayFactory.open(sameDatabase)).thenReturn(targetGateway);

        MigrationRunResult result = engine.run(task, options("{}"), source, sameDatabase,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("error.migration.mongo.sameDatabase");
        verify(sourceGateway, never()).listCollections();
        verify(targetGateway, never()).dropCollection(any());
        verify(targetGateway, never()).insertMany(anyString(), anyList(), anyBoolean());
    }

    @Test
    void theSameServerSpelledTwoWaysIsRefusedBeforeAnythingIsCopied() {
        MigrationConnection sourceAlias = connection(1L, "app");
        MigrationConnection targetAlias = connection(2L, "app");
        targetAlias.setBaseUrl("mongodb://127.0.0.1:27017/app");
        lenient().when(gatewayFactory.open(sourceAlias)).thenReturn(sourceGateway);
        lenient().when(gatewayFactory.open(targetAlias)).thenReturn(targetGateway);
        MongoServerIdentity identity =
                new MongoServerIdentity("app", "rs0", "process-1", "mongo1:27017");
        when(sourceGateway.serverIdentity()).thenReturn(identity);
        when(targetGateway.serverIdentity()).thenReturn(identity);

        MigrationRunResult result = engine.run(task, options("{}"), sourceAlias, targetAlias,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("error.migration.mongo.sameDatabase");
        verify(sourceGateway, never()).listCollections();
        verify(targetGateway, never()).dropCollection(any());
        verify(targetGateway, never()).insertMany(anyString(), anyList(), anyBoolean());
    }

    @Test
    void twoProcessesBehindTheSameHostNameAreNotRefused() {
        lenient().when(sourceGateway.serverIdentity())
                .thenReturn(new MongoServerIdentity("app", "rs0", "process-1", "mongo1:27017"));
        lenient().when(targetGateway.serverIdentity())
                .thenReturn(new MongoServerIdentity("app", "rs0", "process-2", "mongo1:27017"));
        stubOneCollection(List.of(new Document("_id", 1)));

        MigrationRunResult result = engine.run(task, options("{}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        // A restart changes the process id but not the advertised host name; that must not be
        // mistaken for the same database.
        assertThat(result.success()).isTrue();
    }

    @Test
    void identitiesThatNameDifferentDatabasesAreNotRefused() {
        lenient().when(sourceGateway.serverIdentity())
                .thenReturn(new MongoServerIdentity("app", "rs0", "process-1", "mongo1:27017"));
        lenient().when(targetGateway.serverIdentity())
                .thenReturn(new MongoServerIdentity("archive", "rs0", "process-1", "mongo1:27017"));
        stubOneCollection(List.of(new Document("_id", 1)));

        MigrationRunResult result = engine.run(task, options("{}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isTrue();
    }

    @Test
    void withoutAProcessIdTheHostAndReplicaSetStillRefuseTheSameDatabase() {
        MigrationConnection sourceAlias = connection(1L, "app");
        MigrationConnection targetAlias = connection(2L, "app");
        targetAlias.setBaseUrl("mongodb://127.0.0.1:27017/app");
        lenient().when(gatewayFactory.open(sourceAlias)).thenReturn(sourceGateway);
        lenient().when(gatewayFactory.open(targetAlias)).thenReturn(targetGateway);
        lenient().when(sourceGateway.serverIdentity())
                .thenReturn(new MongoServerIdentity("app", "rs0", null, "mongo1:27017"));
        lenient().when(targetGateway.serverIdentity())
                .thenReturn(new MongoServerIdentity("app", "rs0", null, "mongo1:27017"));

        MigrationRunResult result = engine.run(task, options("{}"), sourceAlias, targetAlias,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("error.migration.mongo.sameDatabase");
    }

    @Test
    void anEmptyMatchFailsInsteadOfReportingSuccess() {
        when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("orders", "collection")));

        MigrationRunResult result = engine.run(task,
                options("{\"collectionsText\":\"users*\"}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("no collections matched");
        assertThat(result.indices()).isEmpty();
        verify(targetGateway, never()).insertMany(any(), anyList(), anyBoolean());
    }

    @Test
    void failIfExistsLeavesTheExistingTargetUntouched() {
        stubOneCollection(List.of(new Document("_id", 1)));
        when(targetGateway.collectionExists("orders")).thenReturn(true);

        MigrationRunResult result = engine.run(task,
                options("{\"writeMode\":\"FAIL_IF_EXISTS\"}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isFalse();
        assertThat(result.indices()).singleElement()
                .extracting(IndexResult::status).isEqualTo(Status.FAILED);
        verify(targetGateway, never()).dropCollection(any());
        verify(targetGateway, never()).createCollection(any(), any());
        verify(targetGateway, never()).insertMany(any(), anyList(), anyBoolean());
    }

    @Test
    void overwriteDropsTheTargetBeforeRecreatingIt() {
        stubOneCollection(List.of(new Document("_id", 1)));
        when(targetGateway.collectionExists("orders")).thenReturn(true);

        MigrationRunResult result = engine.run(task,
                options("{\"writeMode\":\"OVERWRITE\"}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isTrue();
        verify(targetGateway).dropCollection("orders");
        verify(targetGateway).createCollection(eq("orders"), any());
    }

    @Test
    void appendSkipsDuplicateIdsAndReportsThem() {
        stubOneCollection(List.of(new Document("_id", 1), new Document("_id", 2),
                new Document("_id", 3)));
        when(targetGateway.collectionExists("orders")).thenReturn(true);
        when(targetGateway.countDocuments("orders")).thenReturn(2L, 4L);
        when(targetGateway.insertMany(eq("orders"), anyList(), eq(false)))
                .thenReturn(new BulkInsertResult(2, 1));

        MigrationRunResult result = engine.run(task,
                options("{\"writeMode\":\"APPEND\"}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isTrue();
        assertThat(result.documentsWritten()).isEqualTo(2);
        assertThat(result.indices()).singleElement()
                .extracting(IndexResult::message).asString().contains("already existed");
    }

    @Test
    void documentsAreBatchedByCountAndProgressIsCumulative() {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            documents.add(new Document("_id", i));
        }
        stubOneCollection(documents, 2);
        List<String> progress = new ArrayList<>();

        MigrationRunResult result = engine.run(task,
                options("{\"batchSize\":2}"), source, target,
                new MigrationCancellation(),
                (collection, read, written) -> progress.add(collection + ":" + read + "/" + written));

        assertThat(result.success()).isTrue();
        assertThat(progress).containsExactly("orders:2/2", "orders:4/4", "orders:5/5");
        verify(targetGateway, times(3)).insertMany(eq("orders"), anyList(), eq(false));
    }

    @Test
    void documentsAreBatchedByBytesAsWellAsCount() {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            documents.add(new Document("_id", i).append("payload", "x".repeat(600_000)));
        }
        stubOneCollection(documents);

        MigrationRunResult result = engine.run(task,
                options("{\"batchSize\":1000,\"maxBatchBytes\":1048576}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isTrue();
        verify(targetGateway, times(3)).insertMany(eq("orders"), anyList(), eq(false));
    }

    @Test
    void aSampleMismatchOnlyWarns() {
        Document first = new Document("_id", 1).append("qty", 2);
        Document second = new Document("_id", 2).append("qty", 3);
        stubOneCollection(List.of(first, second));
        when(sourceGateway.sample("orders", 2)).thenReturn(List.of(first, second));
        when(targetGateway.findByIds(eq("orders"), anyList()))
                .thenReturn(List.of(first, new Document("_id", 2).append("qty", 99)));

        MigrationRunResult result = engine.run(task,
                options("{\"verifySampleSize\":2}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isTrue();
        assertThat(result.indices()).singleElement()
                .extracting(IndexResult::message).asString().contains("sampled documents differ");
    }

    @Test
    void collectionOptionsAndIndexesAreCopiedAndCappedCollectionsStayOrdered() {
        stubOneCollection(List.of(new Document("_id", 1)));
        when(sourceGateway.collectionOptions("orders"))
                .thenReturn(new Document("capped", true).append("size", 1048576L));
        when(sourceGateway.listIndexes("orders")).thenReturn(List.of(
                new Document("name", "_id_").append("key", new Document("_id", 1)),
                new Document("name", "email_1")
                        .append("key", new Document("email", 1))
                        .append("unique", true)));
        when(targetGateway.insertMany(eq("orders"), anyList(), eq(true)))
                .thenReturn(new BulkInsertResult(1, 0));

        MigrationRunResult result = engine.run(task, options("{}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isTrue();
        verify(targetGateway).createCollection("orders",
                new Document("capped", true).append("size", 1048576L));
        verify(targetGateway).createIndex("orders",
                new Document("name", "email_1")
                        .append("key", new Document("email", 1))
                        .append("unique", true));
        verify(targetGateway).insertMany(eq("orders"), anyList(), eq(true));
    }

    @Test
    void switchingCopiesOffLeavesTheTargetUnchanged() {
        stubOneCollection(List.of(new Document("_id", 1)));

        MigrationRunResult result = engine.run(task,
                options("{\"copyCollectionOptions\":false,\"copyIndexes\":false}"),
                source, target, new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isTrue();
        verify(targetGateway).createCollection("orders", new Document());
        verify(targetGateway, never()).createIndex(anyString(), any());
    }

    @Test
    void copyingNoDocumentsSkipsWritesAndCountVerification() {
        stubOneCollection(List.of(new Document("_id", 1)));

        MigrationRunResult result = engine.run(task,
                options("{\"copyDocuments\":false}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isTrue();
        verify(targetGateway, never()).insertMany(anyString(), anyList(), anyBoolean());
        verify(targetGateway, never()).countDocuments(anyString());
    }

    @Test
    void aCountMismatchFailsTheCollection() {
        stubOneCollection(List.of(new Document("_id", 1), new Document("_id", 2)));
        when(targetGateway.countDocuments("orders")).thenReturn(1L);

        MigrationRunResult result = engine.run(task, options("{}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isFalse();
        assertThat(result.indices()).singleElement()
                .extracting(IndexResult::message).asString().contains("count mismatch");
    }

    @Test
    void aViewIsSkippedWithoutBeingTreatedAsCopied() {
        when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("orders_view", "view")));

        MigrationRunResult result = engine.run(task,
                options("{\"collectionsText\":\"orders*\"}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.indices()).singleElement()
                .extracting(IndexResult::status).isEqualTo(Status.SKIPPED);
        assertThat(result.success()).isFalse();
        verify(sourceGateway, never()).find(anyString(), anyInt(), anyBoolean());
    }

    @Test
    void anIndexFailureFailsTheCollectionButDocumentsAreStillCopied() {
        stubOneCollection(List.of(new Document("_id", 1)));
        when(sourceGateway.listIndexes("orders")).thenReturn(List.of(
                new Document("name", "_id_").append("key", new Document("_id", 1)),
                new Document("name", "email_1").append("key", new Document("email", 1))));
        doThrow(new IllegalStateException("index conflict"))
                .when(targetGateway).createIndex(eq("orders"), any());

        MigrationRunResult result = engine.run(task, options("{}"), source, target,
                new MigrationCancellation(), MigrationProgressListener.NONE);

        assertThat(result.success()).isFalse();
        assertThat(result.documentsWritten()).isEqualTo(1);
        assertThat(result.indices()).singleElement()
                .extracting(IndexResult::message).asString()
                .contains("indexes could not be created");
        verify(targetGateway).insertMany(eq("orders"), anyList(), eq(false));
    }

    @Test
    void cancellationBetweenBatchesKeepsPartialCounts() {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            documents.add(new Document("_id", i));
        }
        stubOneCollection(documents, 2);
        MigrationCancellation cancellation = new MigrationCancellation();

        MigrationRunResult result = engine.run(task, options("{\"batchSize\":2}"),
                source, target, cancellation,
                (collection, read, written) -> {
                    if (read >= 2) {
                        cancellation.cancel();
                    }
                });

        assertThat(result.cancelled()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(result.indices()).singleElement()
                .extracting(IndexResult::status).isEqualTo(Status.CANCELLED);
        assertThat(result.documentsWritten()).isEqualTo(4);
    }

    private void stubOneCollection(List<Document> documents) {
        stubOneCollection(documents, 1000);
    }

    private void stubOneCollection(List<Document> documents, int batchSize) {
        lenient().when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("orders", "collection")));
        lenient().when(sourceGateway.collectionOptions("orders")).thenReturn(new Document());
        lenient().when(sourceGateway.listIndexes("orders")).thenReturn(List.of(
                new Document("name", "_id_").append("key", new Document("_id", 1))));
        lenient().when(sourceGateway.find("orders", batchSize, true)).thenReturn(cursor(documents));
        lenient().when(sourceGateway.countDocuments("orders"))
                .thenReturn((long) documents.size());
        lenient().when(targetGateway.collectionExists("orders")).thenReturn(false);
        lenient().when(targetGateway.insertMany(eq("orders"), anyList(), eq(false)))
                .thenAnswer(invocation -> {
                    List<?> batch = invocation.getArgument(1);
                    return new BulkInsertResult(batch.size(), 0);
                });
        lenient().when(targetGateway.countDocuments("orders"))
                .thenReturn((long) documents.size());
    }

    private static MongoDocumentCursor cursor(List<Document> documents) {
        return new MongoDocumentCursor() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < documents.size();
            }

            @Override
            public Document next() {
                return documents.get(index++);
            }

            @Override
            public void close() {
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Document>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static MigrationConnection connection(long id, String name) {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(id);
        connection.setName(name);
        connection.setKind(MigrationConnectionKind.MONGODB);
        connection.setBaseUrl("mongodb://localhost:27017/" + name);
        connection.setAuthType(MigrationConnectionAuthType.NONE);
        return connection;
    }

    private static JsonNode options(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
