package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.etl.EtlCheckpointValue;
import com.qqmu.jync.service.etl.EtlSourceColumn;
import com.qqmu.jync.service.etl.EtlSourceCursor;
import com.qqmu.jync.service.etl.MongoEtlSource;
import com.qqmu.jync.service.etl.mongo.DriverMongoSourceGateway;

/**
 * The native-driver source against a real Community {@code mongod} — the deployment the Atlas
 * SQL path cannot reach. Unit tests mock the gateway; this one proves the driver calls,
 * filter parsing and value rendering line up with an actual server.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoEtlIT {

    @Container
    private static final GenericContainer<?> MONGO =
            new GenericContainer<>(DockerImageName.parse("mongo:6")).withExposedPorts(27017);

    private static String uri() {
        return "mongodb://" + MONGO.getHost() + ':' + MONGO.getMappedPort(27017);
    }

    @BeforeAll
    static void seed() {
        // A separate client: the source closes its own client, and both tests seed-once read it.
        try (MongoClient seed = MongoClients.create(uri())) {
            seed.getDatabase("shop").getCollection("orders").insertMany(List.of(
                    new Document("_id", new ObjectId("64b000000000000000000001"))
                            .append("seq", 1).append("status", "paid")
                            .append("detail", new Document("sku", "A")),
                    new Document("_id", new ObjectId("64b000000000000000000002"))
                            .append("seq", 2).append("status", "paid")
                            .append("detail", new Document("sku", "B")),
                    new Document("_id", new ObjectId("64b000000000000000000003"))
                            .append("seq", 3).append("status", "open")
                            .append("detail", new Document("sku", "C"))));
        }
    }

    private static DatabaseConfig config() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(1L);
        config.setName("mongo-native");
        config.setType(DatabaseType.MONGODB_NATIVE);
        config.setHost(MONGO.getHost());
        config.setPort(MONGO.getMappedPort(27017));
        config.setDatabaseName("shop");
        return config;
    }

    private static EtlTask task(String filter, String resumeKey, EtlWriteMode mode) {
        EtlTask task = new EtlTask();
        task.setSourceCollection("orders");
        task.setSourceFilter(filter);
        task.setResumeKeyColumn(resumeKey);
        task.setWriteMode(mode);
        return task;
    }

    /** Reads the whole cursor once; a forward-only cursor cannot be replayed per column. */
    private static List<List<Object>> rows(EtlSourceCursor cursor) {
        List<List<Object>> rows = new ArrayList<>();
        try {
            while (cursor.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= cursor.columns().size(); i++) {
                    row.add(cursor.getObject(i));
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    private static int indexOf(EtlSourceCursor cursor, String name) {
        for (int i = 0; i < cursor.columns().size(); i++) {
            if (cursor.columns().get(i).name().equals(name)) {
                return i;
            }
        }
        throw new AssertionError("column " + name + " inferred from the sample");
    }

    @Test
    void aCommunityMongodIsReadableWithoutAnyJdbcDriver() throws Exception {
        MongoClient client = MongoClients.create(uri());
        MongoEtlSource source = new MongoEtlSource(new DriverMongoSourceGateway(client, "shop"),
                config());
        try (EtlSourceCursor cursor = source.query(task(null, null, EtlWriteMode.OVERWRITE),
                null)) {
            assertThat(cursor.columns()).extracting(EtlSourceColumn::name)
                    .containsExactly("_id", "detail", "seq", "status");
            List<List<Object>> rows = rows(cursor);
            int seq = indexOf(cursor, "seq");
            int status = indexOf(cursor, "status");
            int detail = indexOf(cursor, "detail");
            int id = indexOf(cursor, "_id");
            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(row -> row.get(seq)).containsExactly(1, 2, 3);
            assertThat(rows).extracting(row -> row.get(status))
                    .containsExactly("paid", "paid", "open");
            assertThat(rows.get(0).get(id))
                    .isEqualTo("64b000000000000000000001");
            // Nested documents become readable JSON text, not a driver-specific object.
            assertThat((String) rows.get(0).get(detail)).contains("\"sku\": \"A\"");
        } finally {
            source.close();
        }
    }

    @Test
    void theFilterAndTheResumeCheckpointReachTheServer() throws Exception {
        MongoClient client = MongoClients.create(uri());
        MongoEtlSource source = new MongoEtlSource(new DriverMongoSourceGateway(client, "shop"),
                config());
        try (EtlSourceCursor cursor = source.query(
                task("{\"status\":\"paid\"}", "seq", EtlWriteMode.APPEND),
                new EtlCheckpointValue(Types.INTEGER, "1"))) {
            assertThat(cursor.resumeKeyIndex()).isEqualTo(3);
            List<List<Object>> rows = rows(cursor);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get(indexOf(cursor, "seq"))).isEqualTo(2);
        } finally {
            source.close();
        }
    }
}
