package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.qqmu.jync.dto.TablePreview;
import com.qqmu.jync.dto.TablePreviewCheck;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.TableMaintenanceService;
import com.qqmu.jync.service.TablePreviewService;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.task.SyncTaskRunner;

/**
 * Real-MySQL regression for the table-level operations: ordered previews, the first-N-row
 * check, single-table reload, and external-baseline adoption plus its background structure
 * verification. The metadata store is the in-memory H2 the CDC harness already uses.
 */
class TableOperationsMysqlIT extends CdcMySqlContainerHarnessIT {

    @Autowired private TablePreviewService previewService;
    @Autowired private TableMaintenanceService tableMaintenance;
    @Autowired private CdcOperationsService cdcOperations;
    @Autowired private CdcPositionStore positionStore;

    @Test
    void previewsAreOrderedAndTheQuickCheckSeesAValueDifference() throws Exception {
        String table = "IT_PREVIEW_CHECK";
        replaceSource(table, "CREATE TABLE " + table
                + " (ID BIGINT PRIMARY KEY, NAME VARCHAR(64))");
        replaceTarget(table, "CREATE TABLE " + table
                + " (ID BIGINT PRIMARY KEY, NAME VARCHAR(64))");
        exec(sourceJdbc(), "INSERT INTO " + table + " (ID, NAME) VALUES"
                + " (3, 'third'), (1, 'first'), (2, 'second')");
        exec(targetJdbc(), "INSERT INTO " + table + " (ID, NAME) VALUES"
                + " (1, 'first'), (2, 'second'), (3, 'third')");

        Project project = projectFor(table, CaptureStrategy.POLLING);

        TablePreview preview = previewService.preview(project.getId(), table,
                TablePreviewService.Side.SOURCE);
        assertThat(preview.orderedByPrimaryKey()).isTrue();
        assertThat(preview.rows()).extracting(row -> row.get(0))
                .containsExactly("1", "2", "3");

        TablePreviewCheck check = previewService.checkPreview(project.getId(), table, 50);
        assertThat(check.status()).isEqualTo(TablePreviewCheck.Status.OK);
        assertThat(check.sourceColumns()).isEqualTo(2);
        assertThat(check.targetColumns()).isEqualTo(2);
        assertThat(check.matchedRows()).isEqualTo(3);

        exec(targetJdbc(), "UPDATE " + table + " SET NAME = 'target-old' WHERE ID = 2");
        TablePreviewCheck changed = previewService.checkPreview(project.getId(), table, 50);
        assertThat(changed.status()).isEqualTo(TablePreviewCheck.Status.MISMATCH);
        assertThat(changed.mismatchColumns()).containsExactly("NAME");
    }

    @Test
    void aStoppedPollingProjectReloadsOnlyTheNamedTableAndTruncatesItsTarget() throws Exception {
        String table = "IT_PREVIEW_RELOAD";
        replaceSource(table, "CREATE TABLE " + table
                + " (ID BIGINT PRIMARY KEY, NAME VARCHAR(64))");
        replaceTarget(table, "CREATE TABLE " + table
                + " (ID BIGINT PRIMARY KEY, NAME VARCHAR(64))");
        exec(sourceJdbc(), "INSERT INTO " + table + " (ID, NAME) VALUES"
                + " (1, 'first'), (2, 'second')");
        exec(targetJdbc(), "INSERT INTO " + table + " (ID, NAME) VALUES"
                + " (1, 'stale'), (99, 'leftover')");

        Project project = projectFor(table, CaptureStrategy.POLLING);

        SyncTaskRunner.Outcome outcome = tableMaintenance.reload(project.getId(), table);

        assertThat(outcome.kind()).isEqualTo(SyncTaskRunner.Outcome.Kind.EXECUTED);
        assertThat(outcome.result()).isPresent();
        assertThat(outcome.result().orElseThrow().isSuccess()).isTrue();
        assertThat(countTarget(table)).isEqualTo(2);
        assertThat(queryTarget("SELECT ID, NAME FROM " + table + " ORDER BY ID"))
                .extracting(row -> row.get("ID") + ":" + row.get("NAME"))
                .containsExactly("1:first", "2:second");
    }

    @Test
    void anAdoptedExternalBaselineGetsItsStructureVerifiedOnTheRunner() throws Exception {
        String table = "IT_EXTERNAL_BASELINE";
        replaceSource(table, "CREATE TABLE " + table
                + " (ID BIGINT PRIMARY KEY, NAME VARCHAR(64))");
        replaceTarget(table, "CREATE TABLE " + table
                + " (ID BIGINT PRIMARY KEY, NAME VARCHAR(64))");
        exec(targetJdbc(), "INSERT INTO " + table + " (ID, NAME) VALUES (1, 'dumped')");

        Project project = projectFor(table, CaptureStrategy.BINLOG_CDC);
        String[] position = currentBinlogPosition();

        cdcOperations.adoptExternalBaseline(project.getId(), position[0],
                Long.parseLong(position[1]), null, null, null);

        waitFor("external baseline structure verification", 30_000, () -> {
            CdcPosition current = positionStore.find(project.getId()).orElse(null);
            return current != null
                    && CdcPosition.VERIFY_OK.equals(current.getExternalBaselineVerifyStatus());
        });
        CdcPosition current = positionStore.find(project.getId()).orElseThrow();
        assertThat(current.hasExternalBaseline()).isTrue();
        assertThat(current.externalBaselineTableNames()).containsExactly(table);
        assertThat(current.isBaselineDone()).isTrue();
    }

    private Project projectFor(String table, CaptureStrategy strategy) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(strategy);
        config.getTables().add(table);
        config.setSyncData(true);
        return newProject("table-ops-" + table, config, false);
    }

    private String[] currentBinlogPosition() throws SQLException {
        try (Connection conn = sourceJdbc();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW MASTER STATUS")) {
            assertThat(rs.next()).isTrue();
            return new String[] {rs.getString(1), String.valueOf(rs.getLong(2))};
        }
    }

    private void replaceSource(String table, String createSql) throws SQLException {
        replace(sourceJdbc(), table, createSql);
    }

    private void replaceTarget(String table, String createSql) throws SQLException {
        replace(targetJdbc(), table, createSql);
    }

    private void replace(Connection conn, String table, String createSql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
            st.execute(createSql);
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
