package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Real-database matrix: Elasticsearch SQL as a read-only polling source into PostgreSQL 16.
 *
 * <p>The X-Pack SQL JDBC driver is not on Maven Central, so the operator supplies the jar
 * exactly as a user would through {@code customJarPath}. No driver jar, no test:
 *
 * <pre>
 * JYNC_ES_IT_URL=jdbc:es://http://host:9200 \
 * JYNC_ES_IT_USER=elastic JYNC_ES_IT_PASSWORD=... \
 * JYNC_ES_IT_DRIVER_JAR=/path/elasticsearch-x-pack-sql-jdbc.jar \
 * [JYNC_ES_IT_DRIVER_CLASS=org.elasticsearch.xpack.sql.jdbc.EsDriver] \
 * [JYNC_ES_IT_INDEX=jync_it_sync] \
 * mvn -q test -Dtest=ElasticsearchSqlSourceIT -Dapi.version=1.44
 * </pre>
 *
 * <p>The test indexes two documents over the REST API (ES SQL is read-only), runs one full
 * load, and reads the rows back from the target. It asserts a simple index-to-table copy,
 * not the SQL dialect's whole surface.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "JYNC_ES_IT_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:esit;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false"
})
class ElasticsearchSqlSourceIT {

    private static final String TARGET_USER = "test";
    private static final String TARGET_PASSWORD = "test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("tgt")
                    .withUsername(TARGET_USER)
                    .withPassword(TARGET_PASSWORD);

    @Autowired private ProjectRepository projectRepository;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;
    @Autowired private SyncContextFactory contextFactory;
    @Autowired private SyncEngine syncEngine;
    @Autowired private DataSourceManager dataSourceManager;
    @Autowired private CryptoUtil cryptoUtil;

    @Test
    void aSimpleIndexIsCopiedToTheJdbcTarget() throws Exception {
        String jdbcUrl = System.getenv("JYNC_ES_IT_URL");
        String driverJar = System.getenv("JYNC_ES_IT_DRIVER_JAR");
        assumeTrue(driverJar != null && !driverJar.isBlank(),
                "JYNC_ES_IT_DRIVER_JAR is required: the X-Pack SQL driver is not on Maven Central");
        String driverClass = envOrDefault("JYNC_ES_IT_DRIVER_CLASS",
                "org.elasticsearch.xpack.sql.jdbc.EsDriver");
        String index = envOrDefault("JYNC_ES_IT_INDEX", "jync_it_sync");
        String user = System.getenv("JYNC_ES_IT_USER");
        String password = System.getenv("JYNC_ES_IT_PASSWORD");

        seedIndex(index, user, password);
        Project project = savedProject(jdbcUrl, driverClass, driverJar, user, password);

        try {
            SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

            assertThat(first.isSuccess()).as("首轮错误: %s", first.getErrors()).isTrue();
            assertThat(first.getRowsInserted()).isEqualTo(2);
            // The target table is named after the index the source metadata reported; read
            // the name back rather than assuming the driver's normalisation.
            String targetTable = onlyTableInPublicSchema();
            assertThat(targetNames(targetTable)).containsValues("ann", "bob");
        } finally {
            deleteIndex(index, user, password);
        }
    }

    private Project savedProject(String jdbcUrl, String driverClass, String driverJar,
            String user, String password) {
        DatabaseConfig source = new DatabaseConfig();
        source.setName("es-it-src");
        source.setType(DatabaseType.ELASTICSEARCH);
        source.setRole(ConnectionRole.SOURCE);
        source.setCustomUrl(jdbcUrl);
        source.setCustomDriver(driverClass);
        source.setCustomJarPath(driverJar);
        source.setUsername(user);
        source.setPassword(cryptoUtil.encrypt(password));
        source = databaseConfigRepository.save(source);

        DatabaseConfig target = new DatabaseConfig();
        target.setName("es-it-tgt");
        target.setType(DatabaseType.POSTGRESQL);
        target.setRole(ConnectionRole.TARGET);
        target.setHost(POSTGRES.getHost());
        target.setPort(POSTGRES.getMappedPort(5432));
        target.setDatabaseName("tgt");
        target.setSchemaName("public");
        target.setUsername(TARGET_USER);
        target.setPassword(cryptoUtil.encrypt(TARGET_PASSWORD));
        target = databaseConfigRepository.save(target);

        Project project = new Project();
        project.setName("elasticsearch-sql-source-it");
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setSyncConfig(contextFactory.serializeConfig(new SyncConfig()));
        project.setEnabled(false);
        return projectRepository.save(project);
    }

    private String onlyTableInPublicSchema() throws Exception {
        DatabaseConfig target = databaseConfigRepository.findByName("es-it-tgt").orElseThrow();
        try (Connection conn = dataSourceManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables"
                     + " WHERE table_schema = 'public' ORDER BY table_name")) {
            assertThat(rs.next()).as("the full load must create one target table").isTrue();
            String name = rs.getString(1);
            assertThat(rs.next()).as("no unexpected second table").isFalse();
            return name;
        }
    }

    private Map<String, String> targetNames(String table) throws Exception {
        Map<String, String> rows = new LinkedHashMap<>();
        DatabaseConfig target = databaseConfigRepository.findByName("es-it-tgt").orElseThrow();
        try (Connection conn = dataSourceManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM \"public\".\"" + table + "\"")) {
            while (rs.next()) {
                rows.put(String.valueOf(rs.getObject("id")), rs.getString("name"));
            }
        }
        return rows;
    }

    /** Two documents, written over REST because ES SQL cannot INSERT. */
    private void seedIndex(String index, String user, String password) throws Exception {
        String base = restBase(System.getenv("JYNC_ES_IT_URL"));
        StringBuilder body = new StringBuilder();
        body.append("{\"index\":{}}\n")
                .append("{\"id\":1,\"name\":\"ann\",\"amount\":10.5,")
                .append("\"updated_at\":\"2026-01-01T00:00:00Z\"}\n")
                .append("{\"index\":{}}\n")
                .append("{\"id\":2,\"name\":\"bob\",\"amount\":20.0,")
                .append("\"updated_at\":\"2026-01-01T00:00:01Z\"}\n");
        send(base + "/" + index + "/_bulk?refresh=true", "POST", body.toString(), user, password);
    }

    private void deleteIndex(String index, String user, String password) throws Exception {
        try {
            send(restBase(System.getenv("JYNC_ES_IT_URL")) + "/" + index, "DELETE", null,
                    user, password);
        } catch (Exception ignored) {
            // Cleanup must not mask the assertion result.
        }
    }

    private static void send(String url, String method, String body, String user,
            String password) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (user != null && !user.isBlank()) {
            String token = Base64.getEncoder()
                    .encodeToString((user + ":" + (password == null ? "" : password))
                            .getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + token);
        }
        HttpResponse<String> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Elasticsearch answered " + response.statusCode()
                    + " for " + method + " " + url + ": " + response.body());
        }
    }

    private static String restBase(String jdbcUrl) {
        return jdbcUrl.replaceFirst("^jdbc:es://", "").replaceAll("/+$", "");
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
