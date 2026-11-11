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
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
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
        when(dataSourceManager.getConnection(any())).thenReturn(connectionExecuting(true));

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
        when(dataSourceManager.getConnection(any())).thenReturn(connectionExecuting(false));

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
    void anOrdinaryAuditRowCannotBeUsedToRunSql() {
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
        when(dataSourceManager.getConnection(any())).thenReturn(connectionExecuting(true));
        CdcPosition at = new CdcPosition();
        at.setBinlogFile("binlog.000042");
        at.setBinlogPos(901743416L);
        when(positionStore.find(3L)).thenReturn(Optional.of(at));
        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.recordPrunedGap("file_info", List.of("suffix"));
        when(cdcConsumerManager.statsFor(3L)).thenReturn(stats);

        service.executeDdl(9L, "ALTER TABLE t ADD COLUMN c INT");

        // The gap is now bounded and names what a replay has to bring over.
        assertThat(log.getGapEndFile()).isEqualTo("binlog.000042");
        assertThat(log.getGapEndPos()).isEqualTo(901743416L);
        assertThat(log.getAffectedTables()).isEqualTo("file_info(suffix)");
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
        when(dataSourceManager.getConnection(any())).thenReturn(connectionExecuting(true));

        service.executeDdl(9L, "ALTER TABLE t DROP COLUMN c");

        // Nothing was left behind, so there is no row to bring over: the task is done.
        assertThat(log.getDdlExecuted()).isTrue();
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
        when(dataSourceManager.getConnection(any())).thenReturn(connectionExecuting(true));

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
        when(dataSourceManager.getConnection(any())).thenReturn(dropMissingColumn());

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
    void theSchemaQualifierIsRewrittenToTheTargetDatabase() {
        // The binlog statement names the source database; only the target may be touched.
        assertThat(CdcDdlTaskService.targetize("ALTER TABLE `src_db`.`t` ADD COLUMN c INT",
                "target_db")).isEqualTo("ALTER TABLE `target_db`.`t` ADD COLUMN c INT");
        // Without a known target name the qualifier is dropped rather than kept.
        assertThat(CdcDdlTaskService.targetize("ALTER TABLE `src_db`.`t` ADD COLUMN c INT", null))
                .isEqualTo("ALTER TABLE `t` ADD COLUMN c INT");
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
        when(dataSourceManager.getConnection(any())).thenReturn(connectionExecuting(true));

        service.executeDdl(9L, "alter table t add column c int comment 'remark'");

        assertThat(log.getDdlExecuted()).isTrue();
        assertThat(log.getTargetDdlSql()).contains("remark");
    }
}
