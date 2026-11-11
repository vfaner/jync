package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.DataSyncService;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;

/**
 * The administrator's "execute on target" and "replay the missing rows" actions: the first
 * must run exactly the edited statement on the project's own target connection and stay
 * idempotent, the second must bring the rows the gap swallowed back without truncating
 * the target.
 */
@ExtendWith(MockitoExtension.class)
class CdcDdlTaskServiceTest {

    @Mock private ChangeLogRepository changeLogRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private DatabaseConfigRepository databaseConfigRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private CdcPositionStore positionStore;
    @Mock private CdcConsumerManager cdcConsumerManager;
    @Mock private SyncContextFactory contextFactory;
    @Mock private DataSyncService dataSyncService;
    @Mock private SyncContext ctx;
    @Mock private MetadataReader metadataReader;
    @Mock private TableMeta tableMeta;

    private CdcDdlTaskService service;

    @BeforeEach
    void setUp() {
        service = new CdcDdlTaskService(changeLogRepository, projectRepository,
                databaseConfigRepository, dataSourceManager, positionStore, cdcConsumerManager,
                contextFactory, dataSyncService);
    }

    private static ChangeLog pendingLog() {
        ChangeLog log = ChangeLog.of(3L, ObjectType.PROJECT, "source_db", ChangeType.ERROR,
                SyncStateWriter.SKIPPED_DDL_PREFIX + "ALTER TABLE t ADD COLUMN c INT");
        log.setResolutionStatus(ChangeLog.RESOLUTION_PENDING);
        log.setDdlExecuted(false);
        log.setTargetDdlSql("ALTER TABLE t ADD COLUMN c INT");
        return log;
    }

    private static Project project() {
        Project project = new Project();
        project.setId(3L);
        project.setTargetDbId(11L);
        return project;
    }

    private static DatabaseConfig target() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(11L);
        config.setName("target-db");
        return config;
    }

    private static Connection connectionExecuting(boolean outcome) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        if (outcome) {
            when(statement.execute(anyString())).thenReturn(false);
        } else {
            when(statement.execute(anyString()))
                    .thenThrow(new SQLException("boom", "42000", 1064));
        }
        return connection;
    }

    private static Connection dropMissingColumn() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException(
                "Can't DROP 'c'; check that column/key exists", "HY000", 1091));
        return connection;
    }

    @Test
    void executingTheEditedDdlMarksItDoneButKeepsTheTaskPendingForReplay() throws Exception {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT");

        assertThat(log.getDdlExecuted()).isTrue();
        // The replay is a later step: PROCESSED now would let retention prune the task.
        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PENDING);
        assertThat(log.getTargetDdlSql()).isEqualTo("ALTER TABLE t ADD COLUMN c INT");
        assertThat(log.getErrorDetail()).isNull();
        assertThat(log.getHandledBy()).isNotBlank();
        assertThat(log.getHandledAt()).isNotNull();
        verify(changeLogRepository).save(log);
    }

    @Test
    void aFailedDdlIsRecordedAsFailedAndCanBeRetried() throws Exception {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(false);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);

        assertThatThrownBy(() -> service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("error.ddl.execute:");

        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_FAILED);
        assertThat(log.getErrorDetail()).contains("boom");
        assertThat(log.getDdlExecuted()).isNotEqualTo(Boolean.TRUE);
        verify(changeLogRepository).save(log);
    }

    @Test
    void anAlreadyExecutedTaskIsNotExecutedTwice() throws Exception {
        ChangeLog log = pendingLog();
        log.setDdlExecuted(true);
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT");

        verify(dataSourceManager, never()).getConnection(any());
    }

    @Test
    void anOrdinaryAuditRowCannotBeUsedToRunSql() throws Exception {
        ChangeLog log = ChangeLog.of(3L, ObjectType.PROJECT, "db", ChangeType.INFO, "baseline rebuilt");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service.executeDdl(9L, "DROP TABLE important"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.ddl.notPending");

        verify(dataSourceManager, never()).getConnection(any());
    }

    @Test
    void renamingATableOrColumnIsRejectedWithoutOpeningAnyConnection() throws Exception {
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(pendingLog()));

        assertThatThrownBy(() -> service.executeDdl(9L, "ALTER TABLE t ADD COLUMN other INT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.ddl.statementChanged");
        assertThatThrownBy(() -> service.executeDdl(9L, "ALTER TABLE other ADD COLUMN c INT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.ddl.statementChanged");

        verify(dataSourceManager, never()).getConnection(any());
    }

    @Test
    void aligningTheTargetClosesTheGapAndNamesTheTablesThatMissedData() throws Exception {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
        CdcPosition at = new CdcPosition();
        at.setBinlogFile("binlog.000042");
        at.setBinlogPos(901743416L);
        when(positionStore.find(3L)).thenReturn(Optional.of(at));
        CdcRuntimeStats stats = new CdcRuntimeStats();
        // The pruned rows belong to the table the statement changes; a task for another
        // table must not inherit them.
        stats.recordPrunedGap("t", List.of("suffix"));
        when(cdcConsumerManager.statsFor(3L)).thenReturn(stats);

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT");

        // The gap is now bounded and names what a replay has to bring over.
        assertThat(log.getGapEndFile()).isEqualTo("binlog.000042");
        assertThat(log.getGapEndPos()).isEqualTo(901743416L);
        assertThat(log.getAffectedTables()).isEqualTo("t(suffix)");
        // The task row now owns the gap. The live counters keep running for the rest of
        // the consumer's life; without clearing, every later DDL task inherits this
        // table's columns and asks for a replay that was already done.
        assertThat(stats.prunedGaps()).isEmpty();
    }

    @Test
    void replayingTheGapReloadsTheAffectedTableAndClosesTheTask() throws Exception {
        ChangeLog log = pendingLog();
        log.setDdlExecuted(true);
        log.setAffectedTables("orders(suffix)");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(contextFactory.build(any(), any(), any(), any())).thenReturn(ctx);
        when(ctx.getSourceConfig()).thenReturn(new DatabaseConfig());
        when(ctx.getTargetConfig()).thenReturn(new DatabaseConfig());
        when(ctx.getSourceSchema()).thenReturn(null);
        when(ctx.getSourceReader()).thenReturn(metadataReader);
        when(metadataReader.readTable(any(), any(), eq("orders"))).thenReturn(tableMeta);
        when(tableMeta.getColumns()).thenReturn(Collections.<ColumnMeta>singletonList(null));
        when(dataSourceManager.getConnection(any())).thenReturn(mock(Connection.class));
        when(dataSyncService.syncTable(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DataSyncService.TableSyncResult());

        service.replayDdl(9L);

        // The affected source table is re-read and upserted; the task is now complete.
        verify(dataSyncService).syncTable(any(), any(), eq(tableMeta), any(), any(), eq(ctx));
        assertThat(log.getReplayDone()).isTrue();
        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PROCESSED);
        assertThat(log.getErrorDetail()).isNull();
    }

    @Test
    void replayingBeforeTheTargetWasAlignedIsRefused() {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service.replayDdl(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.ddl.targetNotAligned");

        verify(dataSyncService, never()).syncTable(any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayingWithoutAnyRecordedTableDoesNotPretendTheTaskIsClosed() {
        ChangeLog log = pendingLog();
        log.setDdlExecuted(true);
        log.setAffectedTables(null);
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service.replayDdl(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.ddl.nothingToReplay");

        // A false closure would convince the operator that the gap was filled.
        assertThat(log.getReplayDone()).isNotEqualTo(Boolean.TRUE);
        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_FAILED);
        verify(dataSyncService, never()).syncTable(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPureDropLeavesNoGapSoTheTaskClosesRightAway() throws Exception {
        ChangeLog log = pendingLog();
        log.setDetails(SyncStateWriter.SKIPPED_DDL_PREFIX
                + "ALTER TABLE t DROP COLUMN c");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);

        service.executeDdl(9L, "ALTER TABLE t DROP COLUMN c");

        // Nothing was left behind, so there is no row to bring over: the task is done.
        assertThat(log.getDdlExecuted()).isTrue();
        assertThat(log.getReplayDone()).isTrue();
        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PROCESSED);
    }

    @Test
    void clausesThatOnlyTouchObjectsOrAttributesOpenNoGap() {
        // None of these changes a column's values, so the projection left nothing behind and
        // a replay has nothing to bring over. Treating them as gaps parked the task in
        // PENDING forever: replay refuses a task with no recorded table.
        assertThat(CdcDdlTaskService.hasColumnGap("ALTER TABLE t ADD INDEX idx_name (c)"))
                .isFalse();
        assertThat(CdcDdlTaskService.hasColumnGap(
                "ALTER TABLE t ADD CONSTRAINT fk FOREIGN KEY (c) REFERENCES other (id)"))
                .isFalse();
        assertThat(CdcDdlTaskService.hasColumnGap(
                "ALTER TABLE t ADD PARTITION (PARTITION p1 VALUES LESS THAN (10))"))
                .isFalse();
        assertThat(CdcDdlTaskService.hasColumnGap("ALTER TABLE t ADD UNIQUE KEY uk (c)"))
                .isFalse();
        assertThat(CdcDdlTaskService.hasColumnGap("ALTER TABLE t ALTER COLUMN c SET DEFAULT 0"))
                .isFalse();
        // ALTER TABLE ... RENAME TO renames the table, not a column: every value already on
        // the target moved with it, and a replay would search for a table that no longer
        // exists under that name. RENAME COLUMN does leave the gap window's rows behind.
        assertThat(CdcDdlTaskService.hasColumnGap("ALTER TABLE t RENAME TO t2")).isFalse();
        assertThat(CdcDdlTaskService.hasColumnGap("ALTER TABLE t RENAME AS t2")).isFalse();
        assertThat(CdcDdlTaskService.hasColumnGap("ALTER TABLE t RENAME COLUMN a TO b"))
                .isTrue();
        // A statement that also adds a column still leaves that column's values behind.
        assertThat(CdcDdlTaskService.hasColumnGap(
                "ALTER TABLE t ADD COLUMN d INT, ADD INDEX i (d)"))
                .isTrue();
    }

    @Test
    void aTableLevelClauseNeedsNoReplaySoTheTaskClosesRightAway() throws Exception {
        ChangeLog log = pendingLog();
        log.setDetails(SyncStateWriter.SKIPPED_DDL_PREFIX + "ALTER TABLE t ADD INDEX idx_name (name)");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);

        service.executeDdl(9L, "ALTER TABLE t ADD INDEX idx_name (name)");

        // No row carries a new column value, so there is no gap to replay.
        assertThat(log.getDdlExecuted()).isTrue();
        assertThat(log.getReplayDone()).isTrue();
        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PROCESSED);
    }

    @Test
    void aGenuineGapWithoutConsumerHistoryFallsBackToTheStatementTable() throws Exception {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT");

        // The consumer recorded nothing (restart) and the live column diff finds nothing
        // (the target is already aligned). The statement itself names the table a replay has
        // to re-read — without it the task could never close.
        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PENDING);
        assertThat(log.getAffectedTables()).isEqualTo("t");
    }

    @Test
    void aGapOnATableTheProjectDoesNotSyncClosesWithoutAReplay() throws Exception {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
        SyncConfig config = new SyncConfig();
        config.setObjectSelectionExplicit(true); // syncs no table at all
        when(contextFactory.parseConfig(any())).thenReturn(config);

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT");

        // Rows of a table the project never syncs were never replicated, so no gap exists.
        assertThat(log.getReplayDone()).isTrue();
        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PROCESSED);
    }

    @Test
    void aStatementThatAlsoAddsAColumnStillNeedsTheReplay() throws Exception {
        ChangeLog log = pendingLog();
        log.setDetails(SyncStateWriter.SKIPPED_DDL_PREFIX
                + "ALTER TABLE t ADD COLUMN d INT, DROP COLUMN c");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN d INT, DROP COLUMN c");

        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PENDING);
        assertThat(log.getReplayDone()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void aDropOfAColumnTheTargetNeverHadCountsAsAligned() throws Exception {
        ChangeLog log = pendingLog();
        log.setDetails(SyncStateWriter.SKIPPED_DDL_PREFIX
                + "ALTER TABLE t DROP COLUMN c");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        // MySQL 1091: the column is not there, so the DROP has nothing to remove.
        Connection connection = dropMissingColumn();
        when(dataSourceManager.getConnection(any())).thenReturn(connection);

        service.executeDdl(9L, "ALTER TABLE t DROP COLUMN c");

        // That is the state the statement asks for — not a failure.
        assertThat(log.getDdlExecuted()).isTrue();
        assertThat(log.getReplayDone()).isTrue();
        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PROCESSED);
        assertThat(log.getErrorDetail()).isNull();
    }

    @Test
    void aSingleAddColumnClauseIsRecognisedAsAGap() {
        // The table identifier must be stripped before the clauses are inspected: a
        // one-clause ADD is exactly the case that used to be mistaken for a harmless
        // DROP, closing the task without a replay.
        assertThat(CdcDdlTaskService.hasColumnGap(
                "ALTER TABLE file_info ADD COLUMN brand_new varchar(20) NULL")).isTrue();
        assertThat(CdcDdlTaskService.addedColumns(
                "ALTER TABLE file_info ADD COLUMN brand_new varchar(20) NULL"))
                .containsExactly("brand_new");
        assertThat(CdcDdlTaskService.hasColumnGap(
                "ALTER TABLE `free_fs_dev`.`file_info` DROP COLUMN `brand_new`,"
                        + " DROP COLUMN `brand_new3`")).isFalse();
        assertThat(CdcDdlTaskService.droppedColumns(
                "ALTER TABLE `free_fs_dev`.`file_info` DROP COLUMN `brand_new`,"
                        + " DROP COLUMN `brand_new3`"))
                .containsExactly("brand_new", "brand_new3");
    }

    @Test
    void theStatementNamesTheTableAReplayWouldHaveToReread() {
        assertThat(CdcDdlTaskService.alteredTable("ALTER TABLE t ADD COLUMN c INT"))
                .isEqualTo("t");
        assertThat(CdcDdlTaskService.alteredTable(
                "ALTER TABLE `src_db`.`file_info` ADD COLUMN c INT")).isEqualTo("file_info");
        assertThat(CdcDdlTaskService.alteredTable(
                "ALTER TABLE [db].[t] ADD COLUMN c INT")).isEqualTo("t");
        assertThat(CdcDdlTaskService.alteredTable("DROP TABLE t")).isNull();
    }

    @Test
    void theSchemaQualifierIsRewrittenToTheTargetDatabase() {
        // The binlog statement names the source database; only the target may be touched.
        assertThat(CdcDdlTaskService.targetize("ALTER TABLE `src_db`.`t` ADD COLUMN c INT",
                "target_db", "`")).isEqualTo("ALTER TABLE `target_db`.`t` ADD COLUMN c INT");
        // Without a known target name the qualifier is dropped rather than kept.
        assertThat(CdcDdlTaskService.targetize("ALTER TABLE `src_db`.`t` ADD COLUMN c INT",
                null, "`")).isEqualTo("ALTER TABLE `t` ADD COLUMN c INT");
    }

    @Test
    void aligningOneTableDoesNotCloseAnOpenTaskForAnotherTable() throws Exception {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
        // A different table that happens to gain a column with the same name.
        ChangeLog otherTable = ChangeLog.of(3L, ObjectType.PROJECT, "source_db",
                ChangeType.ERROR, SyncStateWriter.SKIPPED_DDL_PREFIX
                        + "ALTER TABLE orders ADD COLUMN c INT");
        otherTable.setId(10L);
        otherTable.setResolutionStatus(ChangeLog.RESOLUTION_PENDING);
        when(changeLogRepository.findOpenDdlTasks(3L)).thenReturn(List.of(otherTable));

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT");

        // orders still needs its own column; only t was aligned.
        assertThat(otherTable.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PENDING);
        verify(changeLogRepository, never()).save(otherTable);
    }

    @Test
    void aligningATableClosesAnOlderTaskForTheSameTable() throws Exception {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
        ChangeLog sameTable = ChangeLog.of(3L, ObjectType.PROJECT, "source_db",
                ChangeType.ERROR, SyncStateWriter.SKIPPED_DDL_PREFIX
                        + "ALTER TABLE `source_db`.`T` ADD COLUMN `c` INT");
        sameTable.setId(10L);
        sameTable.setResolutionStatus(ChangeLog.RESOLUTION_PENDING);
        when(changeLogRepository.findOpenDdlTasks(3L)).thenReturn(List.of(sameTable));

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT");

        assertThat(sameTable.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PROCESSED);
        assertThat(sameTable.getReplayDone()).isTrue();
    }

    @Test
    void aCommaInsideATypeDoesNotSplitTheClause() {
        // DECIMAL(10,2) is one clause. Splitting it leaves a "2)" fragment that no clause
        // reader recognises, and the pieces after it are parsed as if they started the
        // table's clause list — the statement's columns are then read wrong.
        assertThat(CdcDdlTaskService.alterClauses(
                "ALTER TABLE t ADD COLUMN amount DECIMAL(10,2) NOT NULL, "
                        + "ADD COLUMN flag TINYINT(1) DEFAULT 0"))
                .containsExactly("ADD COLUMN amount DECIMAL(10,2) NOT NULL",
                        "ADD COLUMN flag TINYINT(1) DEFAULT 0");
    }

    @Test
    void aCommaInsideAQuotedEnumValueDoesNotSplitTheClause() {
        assertThat(CdcDdlTaskService.alterClauses(
                "ALTER TABLE t ADD COLUMN state ENUM('new,yet','done') NOT NULL"))
                .containsExactly("ADD COLUMN state ENUM('new,yet','done') NOT NULL");
    }

    @Test
    void theQualifierIsWrittenInTheTargetsOwnQuoteStyle() {
        assertThat(CdcDdlTaskService.targetize("ALTER TABLE src.t ADD COLUMN c INT",
                "target_db", "\"")).isEqualTo("ALTER TABLE \"target_db\".t ADD COLUMN c INT");
        assertThat(CdcDdlTaskService.targetize("ALTER TABLE src.t ADD COLUMN c INT",
                "target_db", "`")).isEqualTo("ALTER TABLE `target_db`.t ADD COLUMN c INT");
    }

    @Test
    void executingAgainstOracleUsesTheSchemaNotTheServiceName() throws Exception {
        ChangeLog log = pendingLog();
        // The binlog statement carries the source schema qualifier.
        log.setDetails(SyncStateWriter.SKIPPED_DDL_PREFIX
                + "ALTER TABLE src.t ADD COLUMN c INT");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        DatabaseConfig oracle = target();
        oracle.setType(DatabaseType.ORACLE);
        oracle.setDatabaseName("ORCLPDB1");
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(oracle));
        Connection connection = connectionExecuting(true);
        when(connection.getSchema()).thenReturn("APP");
        when(dataSourceManager.getConnection(any())).thenReturn(connection);

        service.executeDdl(9L, "ALTER TABLE src.t ADD COLUMN c INT");

        // The service name addresses the database, not the schema; qualified with it the
        // statement would fail on a healthy target.
        assertThat(log.getTargetDdlSql()).isEqualTo("ALTER TABLE APP.t ADD COLUMN c INT");
    }

    @Test
    void theTargetsTableMappingDecidesWhichTableTheDdlRunsAgainst() throws Exception {
        // The binlog names the source table. A project that maps it to a different target
        // name must get the DDL on the mapped table: running it under the source name
        // touches a table the sync never writes, or fails because that table is absent.
        ChangeLog log = pendingLog();
        log.setDetails(SyncStateWriter.SKIPPED_DDL_PREFIX
                + "ALTER TABLE `src_db`.`orders` ADD COLUMN `note` VARCHAR(64)");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        DatabaseConfig target = target();
        target.setType(DatabaseType.MYSQL);
        target.setDatabaseName("tgt_db");
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
        SyncConfig config = new SyncConfig();
        config.setTableNameMapping(Map.of("orders", "orders_tgt"));
        when(contextFactory.parseConfig(any())).thenReturn(config);

        service.executeDdl(9L,
                "ALTER TABLE `src_db`.`orders` ADD COLUMN `note` VARCHAR(64)");

        assertThat(log.getTargetDdlSql()).isEqualTo(
                "ALTER TABLE tgt_db.orders_tgt ADD COLUMN `note` VARCHAR(64)");
    }

    @Test
    void dismissingAnOpenTaskClosesItWithoutTouchingTheTarget() throws Exception {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));

        service.dismissDdl(9L);

        // The administrator decided the gap needs no action (fixed by hand, or the table
        // is no longer synced). Without this the task stays open forever and keeps the
        // column projection on.
        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PROCESSED);
        assertThat(log.getReplayDone()).isTrue();
        assertThat(log.getHandledBy()).isNotBlank();
        assertThat(log.getHandledAt()).isNotNull();
        verify(changeLogRepository).save(log);
        verify(dataSourceManager, never()).getConnection(any());
    }

    @Test
    void aDismissedTaskCannotBeDismissedTwice() {
        ChangeLog log = pendingLog();
        log.setResolutionStatus(ChangeLog.RESOLUTION_PROCESSED);
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));

        service.dismissDdl(9L);

        verify(changeLogRepository, never()).save(log);
    }

    @Test
    void anOrdinaryAuditRowCannotBeDismissed() {
        ChangeLog log = ChangeLog.of(3L, ObjectType.PROJECT, "db", ChangeType.INFO,
                "baseline rebuilt");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service.dismissDdl(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.ddl.notPending");
    }

    @Test
    void fixingTheCommentAndDialectDetailsIsAllowed() throws Exception {
        ChangeLog log = pendingLog();
        // Binlog QUERY text often arrives mojibake; the admin fixes COMMENT, drops the
        // schema qualifier and changes quoting/case. The structural skeleton is unchanged.
        log.setDetails(SyncStateWriter.SKIPPED_DDL_PREFIX
                + "ALTER TABLE `db`.`t` ADD COLUMN `c` INT COMMENT 'garbled';");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);

        service.executeDdl(9L, "alter table t add column c int comment 'remark'");

        assertThat(log.getDdlExecuted()).isTrue();
        assertThat(log.getTargetDdlSql()).contains("remark");
    }

    @Test
    void theFallbackGapNamesTheSourceTableEvenWhenTheTargetIsMapped() throws Exception {
        ChangeLog log = pendingLog();
        log.setDetails(SyncStateWriter.SKIPPED_DDL_PREFIX
                + "ALTER TABLE `src_db`.`orders` ADD COLUMN `note` VARCHAR(64)");
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
        SyncConfig config = new SyncConfig();
        config.setObjectSelectionExplicit(true);
        config.setTables(Set.of("orders"));
        config.setTableNameMapping(Map.of("orders", "orders_tgt"));
        when(contextFactory.parseConfig(any())).thenReturn(config);

        service.executeDdl(9L, "ALTER TABLE `src_db`.`orders` ADD COLUMN `note` VARCHAR(64)");

        // A replay reads the source table. The recorded name must therefore be the source's;
        // the mapped target name does not exist on the source at all.
        assertThat(log.getAffectedTables()).isEqualTo("orders");
        assertThat(log.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PENDING);
    }

    @Test
    void aligningAMappedTableClosesAnOlderTaskForTheSameSourceTable() throws Exception {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        DatabaseConfig target = target();
        target.setType(DatabaseType.MYSQL);
        target.setDatabaseName("tgt_db");
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
        SyncConfig config = new SyncConfig();
        config.setTableNameMapping(Map.of("t", "t_tgt"));
        when(contextFactory.parseConfig(any())).thenReturn(config);
        ChangeLog older = ChangeLog.of(3L, ObjectType.PROJECT, "source_db", ChangeType.ERROR,
                SyncStateWriter.SKIPPED_DDL_PREFIX + "ALTER TABLE `t` ADD COLUMN `c` INT");
        older.setId(10L);
        older.setResolutionStatus(ChangeLog.RESOLUTION_PENDING);
        when(changeLogRepository.findOpenDdlTasks(3L)).thenReturn(List.of(older));

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT");

        // The executed statement now targets t_tgt, but the open task still names its source
        // table t. Same column on the same logical table: the older task is moot.
        assertThat(older.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PROCESSED);
        assertThat(older.getReplayDone()).isTrue();
    }

    @Test
    void aTaskTakesOnlyItsOwnTablesGapAndLeavesOtherTablesForTheirOwnTask() throws Exception {
        ChangeLog log = pendingLog();
        when(changeLogRepository.findById(9L)).thenReturn(Optional.of(log));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project()));
        when(databaseConfigRepository.findById(11L)).thenReturn(Optional.of(target()));
        Connection connection = connectionExecuting(true);
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.recordPrunedGap("t", List.of("c"));
        stats.recordPrunedGap("other", List.of("x"));
        when(cdcConsumerManager.statsFor(3L)).thenReturn(stats);

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT");

        // other's rows were pruned for another table's column; this task must not claim
        // them and must leave them for the task that owns that table.
        assertThat(log.getAffectedTables()).isEqualTo("t(c)");
        assertThat(stats.prunedGaps()).containsOnlyKeys("other");
    }

    @Test
    void aCommaInsideABackslashEscapedQuoteDoesNotSplitTheClause() {
        List<String> clauses = CdcDdlTaskService.alterClauses(
                "ALTER TABLE t ADD COLUMN a INT,"
                        + " ADD COLUMN b VARCHAR(8) COMMENT 'it\\'s, ok',"
                        + " ADD COLUMN c INT");

        // MySQL accepts backslash-escaped quotes inside a string literal; the comma there is
        // part of the comment, not a clause separator.
        assertThat(clauses).hasSize(3);
        assertThat(clauses.get(1)).isEqualTo("ADD COLUMN b VARCHAR(8) COMMENT 'it\\'s, ok'");
    }
}
