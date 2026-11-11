package com.qqmu.jync.service.migration.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.service.migration.MigrationCancellation;
import com.qqmu.jync.service.migration.MigrationProgressListener;
import com.qqmu.jync.service.migration.MigrationRunResult;
import com.qqmu.jync.service.migration.MigrationRunResult.IndexResult;
import com.qqmu.jync.service.migration.MigrationRunResult.Status;
import com.qqmu.jync.service.migration.connection.KingbaseConnectionFactory;
import com.qqmu.jync.service.migration.kingbase.KingbaseTableWriter;
import com.qqmu.jync.service.migration.kingbase.KingbaseWriterFactory;

@ExtendWith(MockitoExtension.class)
class MongoToKingbaseEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private MongoMigrationGatewayFactory gatewayFactory;

    @Mock
    private MongoMigrationGateway sourceGateway;

    @Mock
    private KingbaseConnectionFactory connectionFactory;

    @Mock
    private KingbaseWriterFactory writerFactory;

    @Mock
    private KingbaseTableWriter writer;

    @Mock
    private KingbaseTableWriter.BatchWriter batch;

    @Mock
    private Connection connection;

    @Mock
    private MigrationConnection source;

    @Mock
    private MigrationConnection target;

    @Mock
    private MigrationTask task;

    private final AtomicInteger pending = new AtomicInteger();
    private MongoToKingbaseEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new MongoToKingbaseEngine(gatewayFactory, connectionFactory, writerFactory);
        lenient().when(gatewayFactory.open(source)).thenReturn(sourceGateway);
        lenient().when(connectionFactory.open(target)).thenReturn(connection);
        lenient().when(connection.getSchema()).thenReturn("public");
        lenient().when(writerFactory.open(connection, "public")).thenReturn(writer);
        lenient().when(writer.tableExists(anyString())).thenReturn(false);
        lenient().when(writer.openBatch(anyString(), any(TableMeta.class), anyBoolean()))
                .thenReturn(batch);
        lenient().doAnswer(invocation -> {
            pending.incrementAndGet();
            return null;
        }).when(batch).add(anyList());
        lenient().when(batch.flush()).thenAnswer(invocation -> {
            int written = pending.get();
            pending.set(0);
            return written;
        });
    }

    @Test
    void overwriteCreatesTheTableFromTheSampleAndInsertsEveryDocument() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("_id", "1").append("qty", 2)),
                List.of(new Document("_id", "1").append("qty", 2),
                        new Document("_id", "2").append("qty", 5)));

        MigrationRunResult result = run();

        assertThat(result.success()).isTrue();
        assertThat(result.indicesSucceeded()).isEqualTo(1);
        assertThat(result.documentsRead()).isEqualTo(2);
        assertThat(result.documentsWritten()).isEqualTo(2);

        ArgumentCaptor<TableMeta> meta = ArgumentCaptor.forClass(TableMeta.class);
        verify(writer).createTable(meta.capture(), eq("orders"));
        assertThat(meta.getValue().columnNames()).containsExactly("_id", "qty");
        assertThat(meta.getValue().getPrimaryKeys()).containsExactly("_id");
        assertThat(meta.getValue().column("_id").orElseThrow().isNullable()).isFalse();
        assertThat(meta.getValue().column("qty").orElseThrow().isNullable()).isTrue();
        assertThat(meta.getValue().column("qty").orElseThrow().getJdbcType())
                .isEqualTo(Types.INTEGER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> rows = ArgumentCaptor.forClass(List.class);
        verify(batch, org.mockito.Mockito.times(2)).add(rows.capture());
        assertThat(rows.getAllValues().get(0)).containsExactly("1", 2);
        assertThat(rows.getAllValues().get(1)).containsExactly("2", 5);
        verify(batch).flush();
    }

    @Test
    void idIsNotAKeyWhenThePrimaryKeyIsDisabled() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("_id", "1").append("qty", 2)),
                List.of(new Document("_id", "1").append("qty", 2)));

        MigrationRunResult result = run(options().put("createPrimaryKey", false));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<TableMeta> meta = ArgumentCaptor.forClass(TableMeta.class);
        verify(writer).createTable(meta.capture(), eq("orders"));
        assertThat(meta.getValue().getPrimaryKeys()).isEmpty();
        assertThat(meta.getValue().column("_id").orElseThrow().isNullable()).isTrue();
        verify(writer).openBatch(eq("orders"), any(TableMeta.class), eq(false));
    }

    @Test
    void appendKeepsAnExistingTableAndInsertsIntoIt() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("_id", "3").append("qty", 1)),
                List.of(new Document("_id", "3").append("qty", 1)));
        when(writer.tableExists("orders")).thenReturn(true);
        when(writer.countRows("orders")).thenReturn(1L, 2L);

        MigrationRunResult result = run(options().put("writeMode", "APPEND"));

        assertThat(result.success()).isTrue();
        verify(writer, never()).dropTable(anyString());
        verify(writer, never()).createTable(any(TableMeta.class), anyString());
        verify(writer).openBatch(eq("orders"), any(TableMeta.class), eq(false));
    }

    @Test
    void appendWithoutAnExistingTableCreatesIt() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("_id", "1")),
                List.of(new Document("_id", "1")));
        when(writer.tableExists("orders")).thenReturn(false);

        MigrationRunResult result = run(options().put("writeMode", "APPEND"));

        assertThat(result.success()).isTrue();
        verify(writer).createTable(any(TableMeta.class), eq("orders"));
    }

    @Test
    void upsertOnIdUsesOnConflictAndDoesNotRequireTheExactRowCount() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("_id", "1")),
                List.of(new Document("_id", "1")));
        when(writer.tableExists("orders")).thenReturn(true);
        when(writer.countRows("orders")).thenReturn(7L);

        MigrationRunResult result = run(options()
                .put("writeMode", "APPEND")
                .put("upsertOnId", true));

        assertThat(result.success()).isTrue();
        verify(writer).openBatch(eq("orders"), any(TableMeta.class), eq(true));
        verify(writer, never()).dropTable(anyString());
    }

    @Test
    void failIfExistsLeavesTheExistingTableUntouched() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("_id", "1")),
                List.of(new Document("_id", "1")));
        when(writer.tableExists("orders")).thenReturn(true);

        MigrationRunResult result = run(options().put("writeMode", "FAIL_IF_EXISTS"));

        assertThat(result.success()).isFalse();
        assertThat(result.indices().get(0).status()).isEqualTo(Status.FAILED);
        verify(writer, never()).dropTable(anyString());
        verify(writer, never()).createTable(any(TableMeta.class), anyString());
        verify(batch, never()).add(anyList());
    }

    @Test
    void anEmptyCollectionIsSkippedAndTheTargetIsNotTouched() throws Exception {
        stubCollection("empty", "collection", List.of(), List.of());

        MigrationRunResult result = run();

        assertThat(result.success()).isFalse();
        assertThat(result.indices().get(0).status()).isEqualTo(Status.SKIPPED);
        assertThat(result.indices().get(0).message()).contains("no documents");
        verify(writer, never()).tableExists(anyString());
        verify(writer, never()).dropTable(anyString());
        verify(writer, never()).createTable(any(TableMeta.class), anyString());
    }

    @Test
    void aViewIsSkipped() throws Exception {
        stubCollection("orders_view", "view",
                List.of(new Document("_id", "1")),
                List.of(new Document("_id", "1")));

        MigrationRunResult result = run();

        assertThat(result.indices().get(0).status()).isEqualTo(Status.SKIPPED);
        verify(writer, never()).createTable(any(TableMeta.class), anyString());
    }

    @Test
    void nestedDocumentsAndArraysAreWrittenAsJsonText() throws Exception {
        Document sample = new Document("_id", "1")
                .append("nested", new Document("a", 1))
                .append("tags", List.of("x", "y"));
        stubCollection("docs", "collection", List.of(sample), List.of(sample));

        MigrationRunResult result = run();

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> rows = ArgumentCaptor.forClass(List.class);
        verify(batch).add(rows.capture());
        List<Object> values = rows.getValue();
        assertThat(values.get(0)).isEqualTo("1");
        assertThat((String) values.get(1)).contains("\"a\"").contains("1");
        assertThat((String) values.get(2)).contains("\"x\"").contains("\"y\"");
    }

    @Test
    void aFieldNamePastTheSixtyThreeByteLimitFailsTheCollectionBeforeAnyDdl() throws Exception {
        String field = "x".repeat(64);
        stubCollection("orders", "collection",
                List.of(new Document("_id", "1").append(field, 2)),
                List.of(new Document("_id", "1").append(field, 2)));

        MigrationRunResult result = run();

        assertThat(result.success()).isFalse();
        assertThat(result.indicesFailed()).isEqualTo(1);
        assertThat(result.indices().get(0).message()).contains(field).contains("63");
        // The server would truncate the name while creating the table and every later insert
        // would target a column that does not exist, so nothing may reach the target.
        verify(writer, never()).tableExists(anyString());
        verify(writer, never()).dropTable(anyString());
        verify(writer, never()).createTable(any(TableMeta.class), anyString());
        verify(writer, never()).openBatch(anyString(), any(TableMeta.class), anyBoolean());
    }

    @Test
    void aMissingIdFieldFailsTheCollection() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("qty", 2)),
                List.of(new Document("qty", 2)));

        MigrationRunResult result = run();

        assertThat(result.success()).isFalse();
        assertThat(result.indices().get(0).status()).isEqualTo(Status.FAILED);
        assertThat(result.indices().get(0).message()).contains("_id");
        verify(writer, never()).createTable(any(TableMeta.class), anyString());
    }

    @Test
    void cancellationBetweenBatchesKeepsThePartialCounters() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("_id", "1")),
                List.of(new Document("_id", "1"), new Document("_id", "2"),
                        new Document("_id", "3"), new Document("_id", "4"),
                        new Document("_id", "5")));
        MigrationCancellation cancellation = new MigrationCancellation();
        MigrationProgressListener progress = (index, read, written) -> {
            if (read >= 2) {
                cancellation.cancel();
            }
        };

        MigrationRunResult result = engine.run(task, options().put("batchSize", 2), source,
                target, cancellation, progress);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.success()).isFalse();
        IndexResult index = result.indices().get(0);
        assertThat(index.status()).isEqualTo(Status.CANCELLED);
        assertThat(index.documentsRead()).isEqualTo(4);
        assertThat(index.documentsWritten()).isEqualTo(4);
    }

    @Test
    void aCountMismatchFailsTheCollection() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("_id", "1")),
                List.of(new Document("_id", "1"), new Document("_id", "2")));
        when(writer.countRows("orders")).thenReturn(1L);

        MigrationRunResult result = run();

        assertThat(result.success()).isFalse();
        assertThat(result.indices().get(0).status()).isEqualTo(Status.FAILED);
        assertThat(result.indices().get(0).message()).contains("count mismatch");
    }

    @Test
    void aMissingSampledIdIsOnlyAWarning() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("_id", "1")),
                List.of(new Document("_id", "1")));
        when(writer.idExists(eq("orders"), any(ColumnMeta.class), any())).thenReturn(false);

        MigrationRunResult result = run();

        assertThat(result.success()).isTrue();
        assertThat(result.indices().get(0).message()).contains("WARN").contains("_id");
    }

    @Test
    void continueOnErrorMovesToTheNextCollection() throws Exception {
        lenient().when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("bad", "collection"),
                new MongoCollectionInfo("orders", "collection")));
        lenient().when(sourceGateway.sample(eq("bad"), anyInt()))
                .thenReturn(List.of(new Document("_id", "1")));
        lenient().when(sourceGateway.sample(eq("orders"), anyInt()))
                .thenReturn(List.of(new Document("_id", "2")));
        when(sourceGateway.find(eq("bad"), anyInt(), anyBoolean()))
                .thenThrow(new IllegalStateException("read failed"));
        lenient().when(sourceGateway.find(eq("orders"), anyInt(), anyBoolean()))
                .thenReturn(cursor(List.of(new Document("_id", "2"))));
        lenient().when(sourceGateway.countDocuments("orders")).thenReturn(1L);
        lenient().when(writer.countRows("orders")).thenReturn(1L);
        lenient().when(writer.idExists(eq("orders"), any(ColumnMeta.class), any()))
                .thenReturn(true);

        MigrationRunResult result = run();

        assertThat(result.indices()).hasSize(2);
        assertThat(result.indices().get(0).status()).isEqualTo(Status.FAILED);
        assertThat(result.indices().get(1).status()).isEqualTo(Status.SUCCESS);
        assertThat(result.indicesSucceeded()).isEqualTo(1);
    }

    @Test
    void aFailedSqlStatementRollsBackSoTheNextCollectionCanRun() throws Exception {
        lenient().when(sourceGateway.listCollections()).thenReturn(List.of(
                new MongoCollectionInfo("bad", "collection"),
                new MongoCollectionInfo("orders", "collection")));
        lenient().when(sourceGateway.sample(eq("bad"), anyInt()))
                .thenReturn(List.of(new Document("_id", "1")));
        lenient().when(sourceGateway.sample(eq("orders"), anyInt()))
                .thenReturn(List.of(new Document("_id", "2")));
        // PostgreSQL-family servers abort the transaction after any failed statement: without a
        // rollback every later collection would fail with "current transaction is aborted".
        when(writer.tableExists("bad")).thenThrow(new SQLException("create failed"));
        lenient().when(sourceGateway.find(eq("orders"), anyInt(), anyBoolean()))
                .thenReturn(cursor(List.of(new Document("_id", "2"))));
        lenient().when(sourceGateway.countDocuments("orders")).thenReturn(1L);
        lenient().when(writer.countRows("orders")).thenReturn(1L);
        lenient().when(writer.idExists(eq("orders"), any(ColumnMeta.class), any()))
                .thenReturn(true);

        MigrationRunResult result = run();

        verify(writer).rollbackQuietly();
        assertThat(result.indices()).hasSize(2);
        assertThat(result.indices().get(0).status()).isEqualTo(Status.FAILED);
        assertThat(result.indices().get(1).status()).isEqualTo(Status.SUCCESS);
    }

    @Test
    void theWriterRunsWithAutoCommitDisabled() throws Exception {
        stubCollection("orders", "collection",
                List.of(new Document("_id", "1")),
                List.of(new Document("_id", "1")));

        run();

        // Both the DDL and every batch are committed explicitly, so auto-commit would make
        // connection.commit() fail on PostgreSQL-family drivers.
        verify(connection).setAutoCommit(false);
    }

    @Test
    void anUnmatchedPatternFailsTheRun() throws Exception {
        when(sourceGateway.listCollections()).thenReturn(List.of());

        MigrationRunResult result = run(options().put("collectionsText", "nothing*"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("no collections matched");
    }

    private MigrationRunResult run() {
        return run(options());
    }

    private MigrationRunResult run(ObjectNode options) {
        return engine.run(task, options, source, target, new MigrationCancellation(),
                MigrationProgressListener.NONE);
    }

    private static ObjectNode options() {
        return JSON.createObjectNode();
    }

    private void stubCollection(String name, String type, List<Document> sample,
                                List<Document> documents) throws Exception {
        lenient().when(sourceGateway.listCollections())
                .thenReturn(List.of(new MongoCollectionInfo(name, type)));
        lenient().when(sourceGateway.sample(name, MongoToKingbaseOptions.DEFAULT_SAMPLE_LIMIT))
                .thenReturn(sample);
        lenient().when(sourceGateway.sample(name, 100)).thenReturn(sample);
        lenient().when(sourceGateway.find(eq(name), anyInt(), anyBoolean()))
                .thenReturn(cursor(documents));
        lenient().when(sourceGateway.countDocuments(name)).thenReturn((long) documents.size());
        lenient().when(writer.countRows(name)).thenReturn((long) documents.size());
        lenient().when(writer.idExists(eq(name), any(ColumnMeta.class), any())).thenReturn(true);
    }

    private static MongoDocumentCursor cursor(List<Document> documents) {
        List<Document> copy = new ArrayList<>(documents);
        return new MongoDocumentCursor() {
            private int position;

            @Override
            public boolean hasNext() {
                return position < copy.size();
            }

            @Override
            public Document next() {
                return copy.get(position++);
            }

            @Override
            public void close() {
                position = copy.size();
            }
        };
    }
}
