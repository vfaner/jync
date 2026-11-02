package com.qqmu.jync.service.migration.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.service.migration.MigrationPrecheckResult;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Level;
import com.qqmu.jync.service.migration.connection.KingbaseConnectionFactory;
import com.qqmu.jync.service.migration.kingbase.KingbaseTableWriter;
import com.qqmu.jync.service.migration.kingbase.KingbaseWriterFactory;

@ExtendWith(MockitoExtension.class)
class MongoToKingbasePrecheckTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private MongoMigrationGatewayFactory gatewayFactory;
    @Mock private MongoMigrationGateway sourceGateway;
    @Mock private KingbaseConnectionFactory kingbaseConnectionFactory;
    @Mock private KingbaseWriterFactory writerFactory;
    @Mock private Connection connection;
    @Mock private KingbaseTableWriter writer;

    private MongoToKingbaseHandler handler;
    private MigrationConnection source;
    private MigrationConnection target;
    private MigrationTask task;

    @BeforeEach
    void setUp() throws Exception {
        handler = new MongoToKingbaseHandler(gatewayFactory, kingbaseConnectionFactory,
                writerFactory);
        source = connection(1L, "source", MigrationConnectionKind.MONGODB, null);
        target = connection(2L, "target", MigrationConnectionKind.KINGBASE, "public");
        task = new MigrationTask();
        task.setId(9L);
        task.setType(MigrationType.MONGODB_TO_KINGBASE);

        lenient().when(gatewayFactory.open(source)).thenReturn(sourceGateway);
        lenient().when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("orders", "collection")));
        lenient().when(sourceGateway.sample(anyString(), anyInt())).thenReturn(sample());
        lenient().when(kingbaseConnectionFactory.open(target)).thenReturn(connection);
        lenient().when(writerFactory.open(connection, "public")).thenReturn(writer);
        lenient().when(writer.tableExists(anyString())).thenReturn(false);
        lenient().when(writer.schemaExists()).thenReturn(true);
    }

    @Test
    void anUnreachableSourceBecomesAnErrorCheckInsteadOfAnException() {
        when(gatewayFactory.open(source)).thenThrow(
                new IllegalStateException("connection refused"));

        MigrationPrecheckResult result = handler.precheck(task, options("{}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.source.unreachable")).isTrue();
    }

    @Test
    void anUnreachableTargetIsAnErrorCheck() throws Exception {
        when(kingbaseConnectionFactory.open(target))
                .thenThrow(new SQLException("connection refused"));

        MigrationPrecheckResult result = handler.precheck(task, options("{}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.target.unreachable")).isTrue();
    }

    @Test
    void anIllegalFieldNameBlocksTheMigration() {
        when(sourceGateway.sample(anyString(), anyInt())).thenReturn(List.of(
                new Document("_id", "1").append("bad name", 2)));

        MigrationPrecheckResult result = handler.precheck(task, options("{}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.mongo.field")).isTrue();
    }

    @Test
    void aCollectionWithoutIdBlocksTheMigration() {
        when(sourceGateway.sample(anyString(), anyInt())).thenReturn(List.of(
                new Document("qty", 2)));

        MigrationPrecheckResult result = handler.precheck(task, options("{}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.mongo.idMissing")).isTrue();
    }

    @Test
    void anEmptyCollectionOnlyWarnsBecauseTheRunSkipsIt() {
        when(sourceGateway.sample(anyString(), anyInt())).thenReturn(List.of());

        MigrationPrecheckResult result = handler.precheck(task, options("{}"), source, target);

        assertThat(result.passed()).isTrue();
        assertThat(has(result, Level.WARN, "migration.precheck.sample.empty")).isTrue();
    }

    @Test
    void aPatternThatMatchesNothingWarnsRatherThanPassingSilently() {
        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"collectionsText\":\"users*\"}"), source, target);

        assertThat(result.passed()).isTrue();
        assertThat(has(result, Level.WARN, "migration.precheck.discovery.empty")).isTrue();
    }

    @Test
    void failIfExistsIsAnErrorWhenTheTargetTableAlreadyExists() throws Exception {
        when(writer.tableExists("orders")).thenReturn(true);

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"writeMode\":\"FAIL_IF_EXISTS\"}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.table.targetExists")).isTrue();
    }

    @Test
    void appendIsAnErrorWhenTheTargetTableLacksAColumn() throws Exception {
        when(writer.tableExists("orders")).thenReturn(true);
        when(writer.targetColumns("orders")).thenReturn(List.of(column("_id", Types.VARCHAR)));

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"writeMode\":\"APPEND\"}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.table.missingColumn")).isTrue();
    }

    @Test
    void appendWarnsWhenAColumnTypeDiffers() throws Exception {
        when(writer.tableExists("orders")).thenReturn(true);
        when(writer.targetColumns("orders")).thenReturn(List.of(
                column("_id", Types.VARCHAR), column("qty", Types.INTEGER)));
        when(sourceGateway.sample(anyString(), anyInt())).thenReturn(List.of(
                new Document("_id", "1").append("qty", "not a number")));

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"writeMode\":\"APPEND\"}"), source, target);

        assertThat(result.passed()).isTrue();
        assertThat(has(result, Level.WARN, "migration.precheck.table.typeMismatch")).isTrue();
    }

    @Test
    void appendIsAnErrorWhenTheTargetHasAnExtraRequiredColumn() throws Exception {
        when(writer.tableExists("orders")).thenReturn(true);
        ColumnMeta tenant = column("tenant", Types.VARCHAR);
        tenant.setNullable(false);
        when(writer.targetColumns("orders")).thenReturn(List.of(
                column("_id", Types.VARCHAR), column("qty", Types.INTEGER), tenant));

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"writeMode\":\"APPEND\"}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR,
                "migration.precheck.table.extraRequiredColumn")).isTrue();
    }

    @Test
    void aSchemaLongerThanSixtyThreeBytesIsAnError() {
        target.setSchemaName("x".repeat(64));

        MigrationPrecheckResult result = handler.precheck(task, options("{}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.schema.invalid")).isTrue();
    }

    @Test
    void anIllegalTargetNameBlocksTheMigration() {
        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"targetNaming\":\"PREFIX\",\"targetPrefix\":\""
                        + "x".repeat(64) + "\"}"),
                source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.index.targetName")).isTrue();
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

    @Test
    void aSampleFailureIsAnErrorCheckInsteadOfAnException() {
        when(sourceGateway.sample(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("sample failed"));

        MigrationPrecheckResult result = handler.precheck(task, options("{}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.sample.failed")).isTrue();
    }

    @Test
    void appendWithUpsertRequiresAUniqueIndexOnId() throws Exception {
        when(writer.tableExists("orders")).thenReturn(true);
        when(writer.targetColumns("orders")).thenReturn(List.of(
                column("_id", Types.VARCHAR), column("qty", Types.INTEGER)));
        when(writer.hasUniqueIndex("orders", "_id")).thenReturn(false);

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"writeMode\":\"APPEND\",\"upsertOnId\":true}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR,
                "migration.precheck.table.idNotUnique")).isTrue();
    }

    @Test
    void appendMatchesColumnNamesCaseSensitively() throws Exception {
        when(writer.tableExists("orders")).thenReturn(true);
        when(writer.targetColumns("orders")).thenReturn(List.of(
                column("_id", Types.VARCHAR), column("QTY", Types.INTEGER)));

        MigrationPrecheckResult result = handler.precheck(task,
                options("{\"writeMode\":\"APPEND\"}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR,
                "migration.precheck.table.missingColumn")).isTrue();
    }

    @Test
    void aTargetSchemaThatDoesNotExistIsAnError() throws Exception {
        when(writer.schemaExists()).thenReturn(false);

        MigrationPrecheckResult result = handler.precheck(task, options("{}"), source, target);

        assertThat(result.passed()).isFalse();
        assertThat(has(result, Level.ERROR, "migration.precheck.schema.missing")).isTrue();
    }

    private static List<Document> sample() {
        return List.of(new Document("_id", "1").append("qty", 2));
    }

    private static ColumnMeta column(String name, int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        return column;
    }

    private static MigrationConnection connection(long id, String name,
                                                  MigrationConnectionKind kind,
                                                  String schemaName) {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(id);
        connection.setName(name);
        connection.setKind(kind);
        if (kind == MigrationConnectionKind.MONGODB) {
            connection.setBaseUrl("mongodb://localhost:27017/" + name);
            connection.setDatabaseName(name);
        } else {
            connection.setBaseUrl("jdbc:kingbase8://localhost:54321/test");
            connection.setDatabaseName("test");
            connection.setSchemaName(schemaName);
        }
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
