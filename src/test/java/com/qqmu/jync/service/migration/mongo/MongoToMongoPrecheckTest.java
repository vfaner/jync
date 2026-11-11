package com.qqmu.jync.service.migration.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.service.migration.MigrationPrecheckResult;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Level;

@ExtendWith(MockitoExtension.class)
class MongoToMongoPrecheckTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private MongoMigrationGatewayFactory gatewayFactory;
    @Mock private MongoMigrationGateway sourceGateway;
    @Mock private MongoMigrationGateway targetGateway;

    private MongoToMongoHandler handler;
    private MigrationConnection source;
    private MigrationConnection target;
    private MigrationTask task;

    @BeforeEach
    void setUp() {
        handler = new MongoToMongoHandler(gatewayFactory);
        source = connection(1L, "source");
        target = connection(2L, "target");
        task = new MigrationTask();
        task.setId(9L);
        task.setType(MigrationType.MONGODB_TO_MONGODB);
        lenient().when(gatewayFactory.open(source)).thenReturn(sourceGateway);
        lenient().when(gatewayFactory.open(target)).thenReturn(targetGateway);
    }

    @Test
    void anUnreachableSourceBecomesAnErrorCheckInsteadOfAnException() {
        when(gatewayFactory.open(source)).thenThrow(new IllegalStateException("connection refused"));

        MigrationPrecheckResult result = handler.precheck(task, options("{}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.source.unreachable")).isTrue();
    }

    @Test
    void aPatternThatMatchesNothingWarnsRatherThanPassingSilently() {
        when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("orders", "collection")));

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"collectionsText\":\"users*\"}"), source, target);

        assertThat(result.passed()).isTrue();
        assertThat(has(result, Level.WARN, "migration.precheck.discovery.empty")).isTrue();
    }

    @Test
    void failIfExistsIsAnErrorWhenTheTargetCollectionAlreadyExists() {
        when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("orders", "collection")));
        when(targetGateway.collectionExists("orders")).thenReturn(true);

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"writeMode\":\"FAIL_IF_EXISTS\"}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.index.targetExists")).isTrue();
    }

    @Test
    void anIllegalTargetNameBlocksTheMigration() {
        when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("orders", "collection")));

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"targetNaming\":\"SUFFIX\",\"targetSuffix\":\"$bad\"}"),
                source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.index.targetName")).isTrue();
    }

    @Test
    void pointingAtTheSameDatabaseIsAnErrorCheck() {
        MigrationConnection sourceAlias = connection(1L, "app");
        MigrationConnection targetAlias = connection(2L, "app");
        targetAlias.setBaseUrl("mongodb://127.0.0.1:27017/app");
        when(gatewayFactory.open(sourceAlias)).thenReturn(sourceGateway);
        when(gatewayFactory.open(targetAlias)).thenReturn(targetGateway);
        when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("orders", "collection")));
        MongoServerIdentity identity =
                new MongoServerIdentity("app", "rs0", "process-1", "mongo1:27017");
        when(sourceGateway.serverIdentity()).thenReturn(identity);
        when(targetGateway.serverIdentity()).thenReturn(identity);

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"writeMode\":\"FAIL_IF_EXISTS\"}"), sourceAlias, targetAlias);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "error.migration.mongo.sameDatabase")).isTrue();
        // The check is decisive, so the per-collection probes are skipped.
        verify(targetGateway, never()).collectionExists(anyString());
    }

    @Test
    void aViewOnlyWarnsBecauseTheRunSkipsIt() {
        when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("orders_view", "view")));

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"collectionsText\":\"orders*\"}"), source, target);

        assertThat(result.passed()).isTrue();
        assertThat(has(result, Level.WARN, "migration.precheck.mongo.view")).isTrue();
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

    private static boolean has(MigrationPrecheckResult result, Level level, String code) {
        return result.checks().stream()
                .anyMatch(check -> check.level() == level && check.code().equals(code));
    }
}
