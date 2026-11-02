package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import com.qqmu.jync.config.SyncProperties;
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
import com.qqmu.jync.service.migration.connection.MongoMigrationClientFactory;
import com.qqmu.jync.service.migration.mongo.DriverMongoMigrationGatewayFactory;
import com.qqmu.jync.service.migration.mongo.MongoToMongoHandler;
import com.qqmu.jync.util.CryptoUtil;

/** A real Community {@code mongod} as both source and target, in two databases. */
@Testcontainers(disabledWithoutDocker = true)
class MongoToMongoIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectId FIRST = new ObjectId("64b000000000000000000001");
    private static final ObjectId SECOND = new ObjectId("64b000000000000000000002");

    @Container
    private static final GenericContainer<?> MONGO =
            new GenericContainer<>(DockerImageName.parse("mongo:6")).withExposedPorts(27017);

    private MongoToMongoHandler handler;

    @BeforeAll
    static void seed() {
        try (MongoClient client = MongoClients.create(uri())) {
            MongoDatabase source = client.getDatabase("source");
            source.drop();

            source.createCollection("orders", new CreateCollectionOptions()
                    .validationOptions(new ValidationOptions()
                            .validator(Filters.gt("qty", 0))
                            .validationLevel(ValidationLevel.MODERATE))
                    .collation(Collation.builder().locale("en").build()));
            source.getCollection("orders").insertMany(List.of(
                    new Document("_id", FIRST).append("qty", 2),
                    new Document("_id", SECOND).append("qty", 3)));

            source.createCollection("events", new CreateCollectionOptions()
                    .capped(true).sizeInBytes(1_000_000).maxDocuments(100));
            source.getCollection("events").insertMany(List.of(
                    new Document("_id", 1).append("kind", "open"),
                    new Document("_id", 2).append("kind", "close")));

            source.createCollection("users");
            source.getCollection("users").insertMany(List.of(
                    new Document("_id", new ObjectId("64b000000000000000000011"))
                            .append("email", "a@example.com").append("score", 10)
                            .append("bio", "first user").append("createdAt", new java.util.Date()),
                    new Document("_id", new ObjectId("64b000000000000000000012"))
                            .append("email", "b@example.com").append("score", 3)
                            .append("bio", "second user")
                            .append("createdAt", new java.util.Date())));
            source.getCollection("users").createIndex(new Document("email", 1),
                    new IndexOptions().unique(true));
            source.getCollection("users").createIndex(new Document("score", 1),
                    new IndexOptions().partialFilterExpression(
                            new Document("score", new Document("$gt", 5))));
            source.getCollection("users").createIndex(new Document("bio", "text"));
            source.getCollection("users").createIndex(new Document("createdAt", 1),
                    new IndexOptions().expireAfter(3600L, java.util.concurrent.TimeUnit.SECONDS));

            source.createCollection("empty");

            List<Bson> pipeline = List.of(new Document("$match",
                    new Document("qty", new Document("$gt", 1))));
            source.createView("orders_view", "orders", pipeline);
        }
    }

    @BeforeEach
    void setUp() {
        MongoMigrationClientFactory clientFactory =
                new MongoMigrationClientFactory(new CryptoUtil(new SyncProperties()));
        handler = new MongoToMongoHandler(new DriverMongoMigrationGatewayFactory(clientFactory));
        try (MongoClient client = MongoClients.create(uri())) {
            client.getDatabase("target").drop();
        }
    }

    @Test
    void everyCollectionIsCopiedWithItsOptionsIndexesAndDocuments() {
        MigrationRunResult result = run("{\"collectionsText\":"
                + "\"orders, events, users, empty, orders_view\"}");

        assertThat(result.success()).isTrue();
        assertThat(status(result, "orders")).isEqualTo(Status.SUCCESS);
        assertThat(status(result, "events")).isEqualTo(Status.SUCCESS);
        assertThat(status(result, "users")).isEqualTo(Status.SUCCESS);
        assertThat(status(result, "empty")).isEqualTo(Status.SUCCESS);
        assertThat(status(result, "orders_view")).isEqualTo(Status.SKIPPED);

        try (MongoClient client = MongoClients.create(uri())) {
            MongoDatabase target = client.getDatabase("target");
            assertThat(target.getCollection("orders").countDocuments()).isEqualTo(2);
            assertThat(target.getCollection("orders").find().into(new ArrayList<>()).stream()
                    .map(document -> document.get("_id")))
                    .containsExactlyInAnyOrder(FIRST, SECOND);
            assertThat(target.getCollection("events").countDocuments()).isEqualTo(2);
            assertThat(target.getCollection("empty").countDocuments()).isZero();

            Document orders = collectionEntry(target, "orders");
            assertThat(orders.get("options", Document.class)).containsKey("validator");
            assertThat(collectionEntry(target, "events").get("options", Document.class))
                    .containsEntry("capped", true);

            List<String> indexNames = target.getCollection("users").listIndexes()
                    .into(new ArrayList<>()).stream()
                    .map(index -> index.getString("name")).toList();
            assertThat(indexNames).contains("_id_", "email_1", "score_1", "bio_text",
                    "createdAt_1");
            assertThat(target.listCollections().into(new ArrayList<>()).stream()
                    .map(entry -> entry.getString("name"))).doesNotContain("orders_view");
        }
    }

    @Test
    void appendKeepsExistingDocumentsAndSkipsDuplicates() {
        try (MongoClient client = MongoClients.create(uri())) {
            client.getDatabase("target").getCollection("orders").insertMany(List.of(
                    new Document("_id", FIRST).append("qty", 99),
                    new Document("_id", new ObjectId("64b000000000000000000099"))
                            .append("qty", 7)));
        }

        MigrationRunResult result = run("{\"collectionsText\":\"orders\","
                + "\"writeMode\":\"APPEND\"}");

        assertThat(result.success()).isTrue();
        assertThat(result.documentsWritten()).isEqualTo(1);
        try (MongoClient client = MongoClients.create(uri())) {
            MongoDatabase target = client.getDatabase("target");
            assertThat(target.getCollection("orders").countDocuments()).isEqualTo(3);
            assertThat(target.getCollection("orders").find(Filters.eq("_id", FIRST))
                    .first().get("qty")).isEqualTo(99);
        }
    }

    @Test
    void failIfExistsLeavesTheExistingTargetUntouched() {
        try (MongoClient client = MongoClients.create(uri())) {
            client.getDatabase("target").getCollection("orders").insertOne(
                    new Document("_id", new ObjectId("64b000000000000000000099"))
                            .append("qty", 7));
        }

        MigrationRunResult result = run("{\"collectionsText\":\"orders\","
                + "\"writeMode\":\"FAIL_IF_EXISTS\"}");

        assertThat(result.success()).isFalse();
        assertThat(status(result, "orders")).isEqualTo(Status.FAILED);
        try (MongoClient client = MongoClients.create(uri())) {
            MongoDatabase target = client.getDatabase("target");
            assertThat(target.getCollection("orders").countDocuments()).isEqualTo(1);
            assertThat(target.getCollection("orders").find().first().get("qty")).isEqualTo(7);
        }
    }

    private MigrationRunResult run(String optionsJson) {
        try {
            JsonNode options = JSON.readTree(optionsJson);
            return handler.run(task(), options, connection("source"), connection("target"),
                    new MigrationCancellation(), MigrationProgressListener.NONE);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Status status(MigrationRunResult result, String sourceName) {
        return result.indices().stream()
                .filter(index -> index.sourceIndex().equals(sourceName))
                .map(IndexResult::status)
                .findFirst()
                .orElseThrow();
    }

    private static Document collectionEntry(MongoDatabase database, String name) {
        return database.listCollections().into(new ArrayList<>()).stream()
                .filter(entry -> name.equals(entry.getString("name")))
                .findFirst()
                .orElseThrow();
    }

    private static MigrationConnection connection(String database) {
        MigrationConnection connection = new MigrationConnection();
        connection.setId("source".equals(database) ? 1L : 2L);
        connection.setName(database);
        connection.setKind(MigrationConnectionKind.MONGODB);
        connection.setBaseUrl(uri() + "/" + database);
        connection.setDatabaseName(database);
        connection.setAuthType(MigrationConnectionAuthType.NONE);
        return connection;
    }

    private static MigrationTask task() {
        MigrationTask task = new MigrationTask();
        task.setId(1L);
        task.setType(MigrationType.MONGODB_TO_MONGODB);
        return task;
    }

    private static String uri() {
        return "mongodb://" + MONGO.getHost() + ':' + MONGO.getMappedPort(27017);
    }
}
