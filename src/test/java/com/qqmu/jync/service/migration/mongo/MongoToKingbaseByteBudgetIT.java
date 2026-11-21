package com.qqmu.jync.service.migration.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.migration.MigrationCancellation;
import com.qqmu.jync.service.migration.MigrationProgressListener;
import com.qqmu.jync.service.migration.MigrationRunResult;
import com.qqmu.jync.service.migration.connection.KingbaseConnectionFactory;
import com.qqmu.jync.service.migration.connection.MongoMigrationClientFactory;
import com.qqmu.jync.service.migration.kingbase.DefaultKingbaseWriterFactory;
import com.qqmu.jync.service.migration.mongo.DriverMongoMigrationGatewayFactory;
import com.qqmu.jync.service.migration.mongo.MongoToKingbaseEngine;
import com.qqmu.jync.util.CryptoUtil;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 真库验证 Mongo→Kingbase 的批字节预算:一批 {@code batchSize} 行、但单行 1.4MB 时,
 * 旧实现会把 ~18MB 全塞进一个 JDBC 批(老驱动先序列化到内存)再提交。
 *
 * <p>Kingbase 不发布容器镜像,沿用 {@link KingbasePollingIT} 的 opt-in 约定;MongoDB 侧
 * 用 Testcontainers 起 {@code mongo:6}:
 *
 * <pre>
 * JYNC_KINGBASE_IT_URL=jdbc:kingbase8://host:54321/test \
 * JYNC_KINGBASE_IT_USER=system JYNC_KINGBASE_IT_PASSWORD=... \
 * mvn -q test -Dtest=MongoToKingbaseByteBudgetIT
 * </pre>
 *
 * <p>判据不是"能跑完"(新旧实现都会跑完),而是进度回调次数:行数上限是 1000,12 条文档
 * 永远碰不到,因此一次以上的批量提交只可能来自字节预算;并且每一批都必须真正落到
 * Kingbase(引擎自带的 count + 抽样校验会失败,否则本测试也失败)。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "JYNC_KINGBASE_IT_URL", matches = ".+")
class MongoToKingbaseByteBudgetIT {

    private static final String SOURCE_SCHEMA = "jync_budget_it";
    private static final int DOCUMENTS = 12;
    /** 估算 1.4MB/条:6 条越过 8MB 预算,12 条共约 16.8MB。 */
    private static final String PAYLOAD = "x".repeat(700_000);

    @Container
    private static final GenericContainer<?> MONGO =
            new GenericContainer<>(DockerImageName.parse("mongo:6"))
                    .withExposedPorts(27017);

    /** The test schema is disposable; leaving it behind would make the next run start dirty. */
    @AfterAll
    static void dropSchema() throws Exception {
        String url = System.getenv("JYNC_KINGBASE_IT_URL");
        if (url == null || url.isBlank()) {
            return; // Kingbase opt-in not set: nothing was created.
        }
        try (Connection conn = DriverManager.getConnection(url,
                System.getenv("JYNC_KINGBASE_IT_USER"),
                System.getenv("JYNC_KINGBASE_IT_PASSWORD"));
             Statement st = conn.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SOURCE_SCHEMA + " CASCADE");
        }
    }

    @Test
    void aBatchOfLargeDocumentsIsSplitByTheByteBudgetAndEveryRowStillLands() throws Exception {
        String mongoUri = "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017)
                + "/jync_budget_it";
        String collection = "bigdocs";
        try (MongoClient client = MongoClients.create(mongoUri)) {
            MongoCollection<Document> target = client.getDatabase("jync_budget_it")
                    .getCollection(collection);
            target.drop();
            List<Document> documents = new ArrayList<>();
            for (int i = 0; i < DOCUMENTS; i++) {
                documents.add(new Document("_id", "d" + i).append("payload", PAYLOAD));
            }
            target.insertMany(documents);
        }

        String url = System.getenv("JYNC_KINGBASE_IT_URL");
        String user = System.getenv("JYNC_KINGBASE_IT_USER");
        String password = System.getenv("JYNC_KINGBASE_IT_PASSWORD");
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + SOURCE_SCHEMA);
            st.execute("DROP TABLE IF EXISTS " + SOURCE_SCHEMA + "." + collection);
        }

        CryptoUtil crypto = new CryptoUtil(new SyncProperties());
        MongoToKingbaseEngine engine = new MongoToKingbaseEngine(
                new DriverMongoMigrationGatewayFactory(new MongoMigrationClientFactory(crypto)),
                new KingbaseConnectionFactory(crypto),
                new DefaultKingbaseWriterFactory(new PostgresDialect()));

        ObjectNode options = new ObjectMapper().createObjectNode();
        // 行数上限远高于文档数:任何提前提交都只能来自字节预算。
        options.put("batchSize", 1000);
        options.put("verifySampleSize", 100);

        List<Long> writtenAfterEachFlush = new ArrayList<>();
        MigrationProgressListener progress =
                (index, read, written) -> writtenAfterEachFlush.add(written);
        MigrationRunResult result = engine.run(new MigrationTask(), options,
                mongoConnection(mongoUri), kingbaseConnection(crypto, url, user, password),
                new MigrationCancellation(), progress);

        assertThat(result.success()).as("run message: %s", result.message()).isTrue();
        assertThat(result.documentsRead()).isEqualTo(DOCUMENTS);
        assertThat(result.documentsWritten()).isEqualTo(DOCUMENTS);
        // 字节预算确实切开了批:至少两次提交,最后一次提交覆盖全部行。
        assertThat(writtenAfterEachFlush).hasSizeGreaterThanOrEqualTo(2);
        assertThat(writtenAfterEachFlush.get(writtenAfterEachFlush.size() - 1))
                .isEqualTo((long) DOCUMENTS);
        // 直接对目标库数一遍,不依赖引擎自报的数字。
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + SOURCE_SCHEMA
                     + "\".\"" + collection + "\"")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(DOCUMENTS);
        }
    }

    private static MigrationConnection mongoConnection(String uri) {
        MigrationConnection connection = new MigrationConnection();
        connection.setName("mongo-budget-it");
        connection.setKind(MigrationConnectionKind.MONGODB);
        connection.setBaseUrl(uri);
        return connection;
    }

    private static MigrationConnection kingbaseConnection(CryptoUtil crypto, String url,
                                                          String user, String password) {
        MigrationConnection connection = new MigrationConnection();
        connection.setName("kingbase-budget-it");
        connection.setKind(MigrationConnectionKind.KINGBASE);
        connection.setBaseUrl(url);
        connection.setSchemaName(SOURCE_SCHEMA);
        connection.setUsername(user);
        connection.setPasswordEnc(crypto.encrypt(password));
        return connection;
    }
}
