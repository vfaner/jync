package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.TableContentAudit;
import com.qqmu.jync.dto.TableContentAudit.Status;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * Exercises the content audit against two real H2 databases: the point of the audit is to
 * compare what two drivers actually hand back, which a mock cannot demonstrate.
 */
class ContentAuditServiceTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private SyncProperties properties;
    private SyncStateWriter stateWriter;
    private ContentAuditService service;
    private SyncContext ctx;
    private TableMeta table;

    @BeforeEach
    void setUp() throws SQLException {
        String tag = UUID.randomUUID().toString().replace("-", "");
        source = DriverManager.getConnection("jdbc:h2:mem:ca_src_" + tag, "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:ca_tgt_" + tag, "sa", "");
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50), FLAG BOOLEAN)");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50), FLAG BOOLEAN)");

        properties = new SyncProperties();
        stateWriter = mock(SyncStateWriter.class);
        service = new ContentAuditService(new DataSyncService(properties), stateWriter, properties);

        table = tableMeta("ITEMS",
                column("ID", Types.BIGINT, "BIGINT"),
                column("NAME", Types.VARCHAR, "VARCHAR"),
                column("FLAG", Types.BOOLEAN, "BOOLEAN"));
        Project project = new Project();
        project.setId(1L);
        project.setName("orders-sync");
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceType(DatabaseType.H2)
                .targetType(DatabaseType.H2)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(500)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        exec(target, "DROP ALL OBJECTS");
        source.close();
        target.close();
    }

    @Test
    void detectsValueDifferenceAtSameRowCount() throws SQLException {
        insert(source, 1, "a", true);
        insert(source, 2, "source-new", true);
        insert(source, 3, "c", false);
        insert(target, 1, "a", true);
        insert(target, 2, "target-old", true);
        insert(target, 3, "c", false);

        TableContentAudit result = auditNow("3");

        assertThat(result.status()).isEqualTo(Status.MISMATCH);
        assertThat(result.sampled()).isEqualTo(3);
        assertThat(result.mismatched()).isEqualTo(1);
        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.missingOnTarget()).isZero();
        assertThat(result.mismatchColumns()).containsExactly("NAME");
    }

    @Test
    void returnsOkWhenAllSampledRowsMatch() throws SQLException {
        insert(source, 1, "a", true);
        insert(source, 2, "b", false);
        insert(target, 1, "a", true);
        insert(target, 2, "b", false);

        TableContentAudit result = auditNow("2");

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.mismatched()).isZero();
        assertThat(result.mismatchColumns()).isEmpty();
    }

    @Test
    void missingTargetSampledRowCountsAsMismatch() throws SQLException {
        insert(source, 1, "a", true);
        insert(source, 2, "b", true);
        insert(source, 3, "c", true);
        insert(target, 1, "a", true);
        insert(target, 3, "c", true);

        TableContentAudit result = auditNow("3");

        assertThat(result.status()).isEqualTo(Status.MISMATCH);
        assertThat(result.missingOnTarget()).isEqualTo(1);
        assertThat(result.mismatched()).isEqualTo(1);
    }

    @Test
    void appliesColumnCleanserToSourceBeforeComparing() throws SQLException {
        insert(source, 1, "  padded  ", true);
        insert(target, 1, "padded", true);
        ColumnRule rule = new ColumnRule();
        rule.setTable("ITEMS");
        rule.setColumn("NAME");
        rule.setType(ColumnRuleType.TRIM);
        ctx.getConfig().getColumnRules().add(rule);

        assertThat(auditNow("1").status()).isEqualTo(Status.OK);
    }

    @Test
    void respectsCursorBound() throws SQLException {
        insert(source, 1, "a", true);
        insert(source, 2, "b", true);
        insert(source, 3, "c", true);
        insert(source, 4, "above-bound", true);
        insert(source, 5, "above-bound", true);
        insert(target, 1, "a", true);
        insert(target, 2, "b", true);
        insert(target, 3, "c", true);

        TableContentAudit result = auditNow("3");

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.sampled()).isEqualTo(3);
    }

    @Test
    void excludesLobColumns() throws SQLException {
        exec(source, "ALTER TABLE ITEMS ADD NOTES CLOB");
        exec(source, "ALTER TABLE ITEMS ADD RAW_BYTES BLOB");
        exec(target, "ALTER TABLE ITEMS ADD NOTES CLOB");
        exec(target, "ALTER TABLE ITEMS ADD RAW_BYTES BLOB");
        table.getColumns().add(column("NOTES", Types.CLOB, "CLOB"));
        table.getColumns().add(column("RAW_BYTES", Types.BLOB, "BLOB"));
        exec(source, "INSERT INTO ITEMS (ID, NAME, FLAG, NOTES) VALUES (1, 'a', TRUE, 'x')");
        exec(target, "INSERT INTO ITEMS (ID, NAME, FLAG, NOTES) VALUES (1, 'a', TRUE, 'y')");
        exec(source, "UPDATE ITEMS SET RAW_BYTES = X'01'");
        exec(target, "UPDATE ITEMS SET RAW_BYTES = X'02'");

        TableContentAudit result = auditNow("1");

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.skippedColumns()).isEqualTo(2);
        assertThat(result.mismatchColumns()).isEmpty();
    }

    @Test
    void skipsTablesWithoutAPrimaryKey() throws SQLException {
        exec(source, "CREATE TABLE NOKEY (NAME VARCHAR(50))");
        TableMeta noKey = tableMeta("NOKEY", column("NAME", Types.VARCHAR, "VARCHAR"));
        noKey.setPrimaryKeys(List.of());

        TableContentAudit result = service.auditNow(source, target, noKey, IDENTITY,
                progress("1"), ctx);

        assertThat(result.status()).isEqualTo(Status.SKIPPED);
        assertThat(result.detail()).isEqualTo("no-primary-key");
    }

    @Test
    void skipsPseudoCursorFullCompareAndNonIncrementalStrategies() throws SQLException {
        assertThat(service.auditNow(source, target, table, CursorStrategy.rowScn("scn"),
                progress("1"), ctx).detail()).isEqualTo("pseudo-cursor");
        assertThat(service.auditNow(source, target, table, CursorStrategy.fullCompare("full"),
                progress("1"), ctx).detail()).isEqualTo("not-incremental");
        assertThat(service.auditNow(source, target, table, CursorStrategy.none("none"),
                progress("1"), ctx).detail()).isEqualTo("not-incremental");
    }

    @Test
    void skipsWhenInitialLoadIsNotDone() throws SQLException {
        SyncProgress progress = progress("1");
        progress.setInitialLoadDone(false);

        assertThat(service.auditNow(source, target, table, IDENTITY, progress, ctx).detail())
                .isEqualTo("initial-load-pending");
    }

    @Test
    void skipsWhenTheKeyCarriesACleansingRule() throws SQLException {
        ColumnRule rule = new ColumnRule();
        rule.setTable("ITEMS");
        rule.setColumn("ID");
        rule.setType(ColumnRuleType.TRIM);
        ctx.getConfig().getColumnRules().add(rule);

        assertThat(auditNow("1").detail()).isEqualTo("cleansed-key");
    }

    @Test
    void skipsWhenSourceEstimateExceedsMaxRows() throws SQLException {
        properties.setContentAuditMaxRows(1);
        table.setApproximateRowCount(100L);

        assertThat(auditNow("1").detail()).isEqualTo("too-large");
    }

    @Test
    void skipsWhenDataSyncIsOff() throws SQLException {
        ctx.getConfig().setSyncData(false);

        assertThat(auditNow("1").detail()).isEqualTo("not-sync-data");
    }

    @Test
    void capsSampleSizeToConfiguredMaximum() throws SQLException {
        properties.setContentAuditSampleSize(2);
        for (int i = 1; i <= 10; i++) {
            insert(source, i, "n" + i, true);
            insert(target, i, "n" + i, true);
        }

        TableContentAudit result = auditNow("10");

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.sampled()).isBetween(1, 2);
    }

    @Test
    void doesNotExposeRowValuesInResult() throws SQLException {
        insert(source, 1, "source-secret-value", true);
        insert(target, 1, "target-secret-value", true);

        TableContentAudit result = auditNow("1");

        assertThat(result.status()).isEqualTo(Status.MISMATCH);
        assertThat(result.mismatchColumns()).containsExactly("NAME");
        assertThat(String.valueOf(result.detail())).doesNotContain("secret-value");
    }

    @Test
    void canonicalizesNumbersTemporalValuesAndBooleans() {
        assertThat(ContentAuditService.canonicalValue(new BigDecimal("5.00")))
                .isEqualTo(ContentAuditService.canonicalValue(5L));
        assertThat(ContentAuditService.canonicalValue(true))
                .isEqualTo(ContentAuditService.canonicalValue(1));
        assertThat(ContentAuditService.canonicalValue(false))
                .isEqualTo(ContentAuditService.canonicalValue(0));
        Timestamp stamp = Timestamp.valueOf("2026-11-03 10:15:30");
        assertThat(ContentAuditService.canonicalValue(
                LocalDateTime.of(2026, 11, 3, 10, 15, 30)))
                .isEqualTo(ContentAuditService.canonicalValue(stamp));
    }

    /**
     * A MySQL/MariaDB TIMESTAMP source arrives as {@link Timestamp}, while the DATETIME
     * column structure sync creates on the target arrives as the driver's wall-clock text
     * (JdbcRowMapper keeps it verbatim so the literal survives the bind). MariaDbPollingIT
     * reported every row as differing on UPDATED_AT until the two forms were reconciled.
     */
    @Test
    void canonicalizesTimestampAgainstMysqlDatetimeText() {
        assertThat(ContentAuditService.canonicalValue("2026-01-02 00:00:00"))
                .isEqualTo(ContentAuditService.canonicalValue(
                        Timestamp.valueOf("2026-01-02 00:00:00")));
        assertThat(ContentAuditService.canonicalValue("2026-01-02 00:00:00.123456"))
                .isEqualTo(ContentAuditService.canonicalValue(
                        Timestamp.valueOf("2026-01-02 00:00:00.123456")));
        // Ordinary text is still compared verbatim.
        assertThat(ContentAuditService.canonicalValue("hello"))
                .isNotEqualTo(ContentAuditService.canonicalValue("HELLO"));
        assertThat(ContentAuditService.canonicalValue("2026-01-02"))
                .isEqualTo(ContentAuditService.canonicalValue("2026-01-02"));
    }

    @Test
    void intervalSuppressesSecondAuditAndManualBypassesIt() throws SQLException {
        insert(source, 1, "a", true);
        insert(target, 1, "a", true);
        ContentAuditService.CycleBudget budget = service.newCycleBudget();

        assertThat(service.maybeAudit(source, target, table, IDENTITY, progress("1"), ctx, budget))
                .isNotNull();
        assertThat(service.maybeAudit(source, target, table, IDENTITY, progress("1"), ctx, budget))
                .isNull();
        assertThat(service.auditNow(source, target, table, IDENTITY, progress("1"), ctx).status())
                .isEqualTo(Status.OK);
    }

    @Test
    void ineligibleTableDoesNotConsumeTheCycleSlotOrRefreshInterval() throws SQLException {
        insert(source, 1, "a", true);
        insert(target, 1, "a", true);
        ContentAuditService.CycleBudget budget = service.newCycleBudget();

        // A keyless table can never be audited; its answer is SKIPPED and it must leave
        // both the per-cycle slot and the per-project interval untouched.
        TableMeta noKey = tableMeta("NOKEY", column("NAME", Types.VARCHAR, "VARCHAR"));
        noKey.setPrimaryKeys(List.of());
        TableContentAudit skipped = service.maybeAudit(source, target, noKey, IDENTITY,
                progress("1"), ctx, budget);
        assertThat(skipped.status()).isEqualTo(Status.SKIPPED);
        assertThat(skipped.detail()).isEqualTo("no-primary-key");

        // An eligible table later in the same cycle still gets audited: under the old
        // ordering the skipped table had spent the single slot and refreshed the interval.
        assertThat(service.maybeAudit(source, target, table, IDENTITY, progress("1"), ctx, budget))
                .isNotNull();
    }

    @Test
    void onlyMismatchesAreRecordedAndOnlyOncePerFingerprint() throws SQLException {
        insert(source, 1, "source-new", true);
        insert(target, 1, "target-old", true);
        TableContentAudit mismatch = auditNow("1");

        service.record(ctx, mismatch);
        service.record(ctx, mismatch);

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(stateWriter, times(1)).recordLog(eq(1L), eq(ObjectType.TABLE), eq("ITEMS"),
                eq(ChangeType.INFO), details.capture(), eq(true), anyLong(), eq(0));
        assertThat(details.getValue())
                .matches("Content audit: table ITEMS: \\d+ sampled, \\d+ row\\(s\\) differ.*")
                .doesNotContain("row count audit");

        clearInvocations(stateWriter);
        service.record(ctx, new TableContentAudit("ITEMS", Status.OK, 1, 1, 0, 0, 0,
                List.of(), null, 1L));
        verifyNoInteractions(stateWriter);
    }

    private TableContentAudit auditNow(String cursor) throws SQLException {
        return service.auditNow(source, target, table, IDENTITY, progress(cursor), ctx);
    }

    private SyncProgress progress(String cursor) {
        SyncProgress progress = new SyncProgress();
        progress.setLastSyncValue(cursor);
        progress.setInitialLoadDone(true);
        return progress;
    }

    private static TableMeta tableMeta(String name, ColumnMeta... columns) {
        TableMeta meta = new TableMeta();
        meta.setName(name);
        meta.setColumns(new java.util.ArrayList<>(List.of(columns)));
        meta.setPrimaryKeys(List.of("ID"));
        return meta;
    }

    private static ColumnMeta column(String name, int jdbcType, String typeName) {
        ColumnMeta meta = new ColumnMeta();
        meta.setName(name);
        meta.setJdbcType(jdbcType);
        meta.setTypeName(typeName);
        return meta;
    }

    private void insert(Connection conn, long id, String name, boolean flag) throws SQLException {
        exec(conn, "INSERT INTO ITEMS (ID, NAME, FLAG) VALUES (" + id + ", '" + name + "', "
                + flag + ")");
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
