package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mongodb.MongoSocketOpenException;
import com.mongodb.ServerAddress;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.etl.mongo.MongoCursorHandle;
import com.qqmu.jync.service.etl.mongo.MongoSourceGateway;

/**
 * The Mongo source is the only part of the ETL path that does not speak SQL. The gateway is
 * mocked because a live server would tell us the rows but not the sample size, the sort and
 * the filter actually sent to the driver.
 */
class MongoEtlSourceTest {

    private static final String COLLECTION = "orders";
    private static final ObjectId ID = new ObjectId("507f1f77bcf86cd799439011");

    private MongoSourceGateway gateway;
    private MongoCursorHandle handle;
    private DatabaseConfig config;
    private List<Document> samples;

    @BeforeEach
    void setUp() {
        gateway = mock(MongoSourceGateway.class);
        handle = mock(MongoCursorHandle.class);
        config = new DatabaseConfig();
        config.setId(3L);
        config.setType(DatabaseType.MONGODB_NATIVE);
        samples = List.of(new Document("_id", ID)
                .append("seq", 1)
                .append("name", "first")
                .append("nested", new Document("a", 1)));
        when(gateway.collectionExists(config, COLLECTION)).thenReturn(true);
        when(gateway.sample(eq(config), eq(COLLECTION), any(Document.class), eq(200), anyInt()))
                .thenReturn(samples);
        when(gateway.find(eq(config), eq(COLLECTION), any(Document.class), any(),
                eq(1000), anyInt())).thenReturn(handle);
    }

    @Test
    void samplesWithTheTaskFilterAndTimeout() {
        MongoEtlSource source = source();
        EtlTask task = task(EtlWriteMode.OVERWRITE, null);
        task.setSourceFilter("{\"status\":\"active\"}");
        task.setTimeoutSeconds(15);

        source.query(task, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Document> filter = ArgumentCaptor.forClass(Document.class);
        verify(gateway).sample(eq(config), eq(COLLECTION), filter.capture(), eq(200), eq(15));
        assertThat(filter.getValue()).isEqualTo(new Document("status", "active"));
    }

    @Test
    void aResumableRunSortsByTheKeyAndCombinesTheCheckpointWithAnd() {
        MongoEtlSource source = source();
        EtlTask task = task(EtlWriteMode.APPEND, "seq");
        task.setSourceFilter("{\"status\":\"active\"}");

        EtlSourceCursor cursor = source.query(task,
                new EtlCheckpointValue(Types.INTEGER, "41"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Document> filter = ArgumentCaptor.forClass(Document.class);
        verify(gateway).find(eq(config), eq(COLLECTION), filter.capture(),
                eq(new Document("seq", 1)), eq(1000), anyInt());
        assertThat(filter.getValue()).isEqualTo(new Document("$and", List.of(
                new Document("status", "active"),
                new Document("seq", new Document("$gt", 41)))));
        assertThat(cursor.resumeKeyIndex()).isEqualTo(4);
    }

    @Test
    void aRunWithoutACheckpointStillSortsButHasNoGreaterThanCondition() {
        MongoEtlSource source = source();
        EtlTask task = task(EtlWriteMode.APPEND, "seq");

        source.query(task, null);

        verify(gateway).find(eq(config), eq(COLLECTION), eq(new Document()),
                eq(new Document("seq", 1)), eq(1000), eq(0));
    }

    @Test
    void anOverwriteRunNeitherSortsNorResumes() {
        MongoEtlSource source = source();
        EtlTask task = task(EtlWriteMode.OVERWRITE, "seq");

        EtlSourceCursor cursor = source.query(task, null);

        verify(gateway).find(eq(config), eq(COLLECTION), eq(new Document()),
                isNull(), eq(1000), eq(0));
        assertThat(cursor.resumeKeyIndex()).isEqualTo(-1);
    }

    @Test
    void inferredColumnsComeFromTheSample() {
        MongoEtlSource source = source();

        EtlSourceCursor cursor = source.query(task(EtlWriteMode.OVERWRITE, null), null);

        assertThat(cursor.columns()).extracting(EtlSourceColumn::name)
                .containsExactly("_id", "name", "nested", "seq");
    }

    @Test
    void rowValuesAreRenderedAsTheirColumnType() throws SQLException {
        when(handle.next()).thenReturn(true, false);
        when(handle.current()).thenReturn(new Document("_id", ID)
                .append("seq", 7)
                .append("name", "first")
                .append("nested", new Document("a", 1)));
        MongoEtlSource source = source();
        EtlSourceCursor cursor = source.query(task(EtlWriteMode.OVERWRITE, null), null);

        assertThat(cursor.next()).isTrue();
        assertThat(cursor.getObject(1)).isEqualTo(ID.toHexString());
        assertThat(cursor.getObject(2)).isEqualTo("first");
        assertThat(cursor.getObject(3)).isInstanceOf(String.class);
        assertThat(cursor.getObject(4)).isEqualTo(7);
        assertThat(cursor.next()).isFalse();
    }

    @Test
    void theResumeKeyIsReadBackInItsCheckpointTextForm() throws SQLException {
        when(handle.next()).thenReturn(true, false);
        when(handle.current()).thenReturn(new Document("_id", ID).append("seq", 7));
        MongoEtlSource source = source();
        EtlSourceCursor cursor = source.query(task(EtlWriteMode.APPEND, "seq"), null);

        cursor.next();

        assertThat(cursor.getString(4)).isEqualTo("7");
    }

    @Test
    void aMissingCollectionIsASourceError() {
        when(gateway.collectionExists(config, COLLECTION)).thenReturn(false);

        assertThatThrownBy(() -> source().query(task(EtlWriteMode.OVERWRITE, null), null))
                .isInstanceOf(EtlSourceException.class)
                .satisfies(e -> assertThat(((EtlSourceException) e).messageKey())
                        .isEqualTo("error.etl.mongo.collection.missing"));
        verify(gateway, never()).sample(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void anEmptySampleIsAProgramError() {
        when(gateway.sample(eq(config), eq(COLLECTION), any(Document.class), eq(200), anyInt()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> source().query(task(EtlWriteMode.OVERWRITE, null), null))
                .isInstanceOf(EtlProgramException.class)
                .hasMessage("error.etl.mongo.sample.empty");
        verify(gateway, never()).find(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void anInvalidFieldNameFailsBeforeTheFind() {
        when(gateway.sample(eq(config), eq(COLLECTION), any(Document.class), eq(200), anyInt()))
                .thenReturn(List.of(new Document("bad-name", 1)));

        assertThatThrownBy(() -> source().query(task(EtlWriteMode.OVERWRITE, null), null))
                .isInstanceOf(EtlProgramException.class)
                .hasMessage("error.etl.mongo.field.invalid");
        verify(gateway, never()).find(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void aResumeKeyOutsideTheSampleIsAProgramError() {
        assertThatThrownBy(() -> source().query(task(EtlWriteMode.APPEND, "unsampled"), null))
                .isInstanceOf(EtlProgramException.class)
                .hasMessage("error.etl.mongo.resume.missing");
    }

    @Test
    void aBlockedFilterIsRefusedBeforeTheSample() {
        EtlTask task = task(EtlWriteMode.OVERWRITE, null);
        task.setSourceFilter("{\"$where\":\"true\"}");

        assertThatThrownBy(() -> source().query(task, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.filter.blocked");
        verify(gateway, never()).sample(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void aNonConnectionDriverFailureIsASourceErrorNotAConnectionError() {
        when(gateway.collectionExists(config, COLLECTION))
                .thenThrow(new IllegalStateException("cannot sort by this field"));

        assertThatThrownBy(() -> source().query(task(EtlWriteMode.OVERWRITE, null), null))
                .isInstanceOf(EtlSourceException.class)
                .satisfies(e -> {
                    EtlSourceException failure = (EtlSourceException) e;
                    // Only a socket/timeout failure is a connection problem; anything else
                    // would send the operator to the wrong check.
                    assertThat(failure.messageKey())
                            .isEqualTo("etl.precheck.mongo.source.error");
                    assertThat(failure.detail()).isEqualTo("cannot sort by this field");
                });
    }

    @Test
    void aSocketFailureBecomesAConnectionError() {
        when(gateway.collectionExists(config, COLLECTION))
                .thenThrow(new MongoSocketOpenException("connect refused",
                        new ServerAddress("localhost", 27017), new ConnectException("refused")));

        assertThatThrownBy(() -> source().query(task(EtlWriteMode.OVERWRITE, null), null))
                .isInstanceOf(EtlSourceException.class)
                .satisfies(e -> assertThat(((EtlSourceException) e).messageKey())
                        .isEqualTo("etl.precheck.mongo.connection.error"));
    }

    @Test
    void probeReportsTheCollectionTheSampleAndTheResumeKey() {
        MongoEtlSource source = source();
        EtlTask task = task(EtlWriteMode.APPEND, "seq");

        EtlSourceProbe probe = source.probe(task, 15);

        assertThat(probe.columns()).extracting(EtlSourceColumn::name)
                .containsExactly("_id", "name", "nested", "seq");
        assertThat(probe.resumeKeyIndex()).isEqualTo(4);
        assertThat(probe.issues()).extracting(EtlSourceIssue::code).contains(
                "etl.precheck.mongo.collection.ok", "etl.precheck.mongo.sample.ok",
                "etl.precheck.mongo.filter.ok", "etl.precheck.mongo.resume.ok");
    }

    @Test
    void probeReportsAMissingCollectionWithoutSampling() {
        when(gateway.collectionExists(config, COLLECTION)).thenReturn(false);

        EtlSourceProbe probe = source().probe(task(EtlWriteMode.OVERWRITE, null), 15);

        assertThat(probe.columns()).isEmpty();
        assertThat(probe.issues()).extracting(EtlSourceIssue::code)
                .contains("etl.precheck.mongo.collection.missing");
        verify(gateway, never()).sample(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void probeReportsABlockedFilter() {
        EtlTask task = task(EtlWriteMode.OVERWRITE, null);
        task.setSourceFilter("{\"a\":{\"$function\":{}}}");

        EtlSourceProbe probe = source().probe(task, 15);

        assertThat(probe.issues()).extracting(EtlSourceIssue::code)
                .contains("etl.precheck.mongo.filter.blocked");
        verify(gateway, never()).sample(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void probeReportsAnEmptySample() {
        when(gateway.sample(eq(config), eq(COLLECTION), any(Document.class), eq(200), anyInt()))
                .thenReturn(List.of());

        EtlSourceProbe probe = source().probe(task(EtlWriteMode.OVERWRITE, null), 15);

        assertThat(probe.columns()).isEmpty();
        assertThat(probe.issues()).extracting(EtlSourceIssue::code)
                .contains("etl.precheck.mongo.sample.empty");
    }

    @Test
    void probeReportsAnInvalidField() {
        when(gateway.sample(eq(config), eq(COLLECTION), any(Document.class), eq(200), anyInt()))
                .thenReturn(List.of(new Document("bad-name", 1)));

        EtlSourceProbe probe = source().probe(task(EtlWriteMode.OVERWRITE, null), 15);

        EtlSourceIssue issue = probe.issues().stream()
                .filter(candidate -> "etl.precheck.mongo.field.invalid".equals(candidate.code()))
                .findFirst()
                .orElseThrow();
        assertThat(issue.detail()).isEqualTo("bad-name");
        assertThat(probe.columns()).isEmpty();
    }

    @Test
    void probeReportsAMissingResumeKey() {
        EtlSourceProbe probe = source().probe(task(EtlWriteMode.APPEND, "unsampled"), 15);

        assertThat(probe.resumeKeyIndex()).isEqualTo(-1);
        assertThat(probe.issues()).extracting(EtlSourceIssue::code)
                .contains("etl.precheck.mongo.resume.error");
    }

    /**
     * A field may be absent from the first sampled document and present in the others. Looking
     * only at sample one would warn about a null resume value that does not exist.
     */
    @Test
    void probeUsesTheFirstNonNullResumeValue() {
        when(gateway.sample(eq(config), eq(COLLECTION), any(Document.class), eq(200), anyInt()))
                .thenReturn(List.of(
                        new Document("_id", ID),
                        new Document("_id", new ObjectId()).append("seq", 5)));

        EtlSourceProbe probe = source().probe(task(EtlWriteMode.APPEND, "seq"), 15);

        assertThat(probe.resumeKeyIndex()).isEqualTo(2);
        assertThat(probe.issues()).extracting(EtlSourceIssue::code)
                .contains("etl.precheck.mongo.resume.ok")
                .doesNotContain("etl.precheck.mongo.resume.null");
    }

    @Test
    void closingTheSourceClosesTheGateway() {
        source().close();

        verify(gateway).close();
    }

    private MongoEtlSource source() {
        return new MongoEtlSource(gateway, config);
    }

    private static EtlTask task(EtlWriteMode mode, String resumeKey) {
        EtlTask task = new EtlTask();
        task.setId(7L);
        task.setSourceCollection(COLLECTION);
        task.setSourceSql("");
        task.setTargetTable("orders_copy");
        task.setWriteMode(mode);
        task.setResumeKeyColumn(resumeKey);
        return task;
    }
}
