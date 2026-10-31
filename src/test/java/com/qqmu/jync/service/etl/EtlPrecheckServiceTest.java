package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * The precheck must answer "will this run fail?" by reading, never by writing.
 *
 * <p>It also has to agree with the runner: the wrapper, the identifier rule, the resume-key
 * lookup and the target-name qualification are shared, so a green precheck that the run then
 * rejects is a defect in this test, not an acceptable outcome.
 */
class EtlPrecheckServiceTest {

    private static final String SOURCE_URL =
            "jdbc:h2:mem:precheck-src-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    private static final String TARGET_URL =
            "jdbc:h2:mem:precheck-dst-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    private final DatabaseConfigService configService = mock(DatabaseConfigService.class);
    private final EtlPrecheckService service =
            new EtlPrecheckService(configService, mock(DataSourceManager.class));

    private final EtlConnectionProvider connections = config -> DriverManager.getConnection(
            config.getId() == null || config.getId() == 1L ? SOURCE_URL : TARGET_URL, "sa", "");

    private DatabaseConfig source;
    private DatabaseConfig target;

    @BeforeEach
    void setUp() throws SQLException {
        reset(SOURCE_URL);
        reset(TARGET_URL);
        source = config(1L);
        target = config(2L);
    }

    @Test
    void aValidSetupPassesAndListsTheResultColumns() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY, name VARCHAR(20))");
        execute(TARGET_URL, "CREATE TABLE dst (id INT, name VARCHAR(20))");

        EtlPrecheckResult result = service.check(form("SELECT id, name FROM src", "dst",
                EtlWriteMode.APPEND), source, target, connections);

        assertThat(result.passed()).isTrue();
        assertThat(result.columns()).extracting(EtlPrecheckResult.ColumnInfo::name)
                .containsExactly("ID", "NAME");
        assertThat(codes(result)).contains("etl.precheck.source.ok", "etl.precheck.target.ok");
        assertThat(result.checks()).extracting(EtlPrecheckResult.Check::level)
                .doesNotContain(EtlPrecheckResult.Level.ERROR);
    }

    @Test
    void aBrokenExtractionSqlIsReportedAsAnError() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");

        EtlPrecheckResult result = service.check(form("SELECT nope FROM src", "dst",
                EtlWriteMode.APPEND), source, target, connections);

        assertThat(result.passed()).isFalse();
        EtlPrecheckResult.Check failure = check(result, "etl.precheck.source.error");
        assertThat(failure.level()).isEqualTo(EtlPrecheckResult.Level.ERROR);
        assertThat(failure.detail()).containsIgnoringCase("NOPE");
    }

    @Test
    void anExpressionWithoutAnAliasIsRejectedBeforeTheRun() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");

        EtlPrecheckResult result = service.check(form("SELECT id + 1 FROM src", "dst",
                EtlWriteMode.APPEND), source, target, connections);

        assertThat(result.passed()).isFalse();
        assertThat(check(result, "etl.precheck.column.error").detail()).contains("ID");
    }

    @Test
    void aResumeKeyMissingFromTheResultIsAnError() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");
        EtlTask form = form("SELECT id FROM src", "dst", EtlWriteMode.APPEND);
        form.setResumeKeyColumn("not_there");

        EtlPrecheckResult result = service.check(form, source, target, connections);

        assertThat(result.passed()).isFalse();
        assertThat(check(result, "etl.precheck.resume.error").detail()).isEqualTo("not_there");
    }

    @Test
    void aPresentResumeKeyIsReportedAsOk() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");
        EtlTask form = form("SELECT id FROM src", "dst", EtlWriteMode.APPEND);
        form.setResumeKeyColumn("id");

        EtlPrecheckResult result = service.check(form, source, target, connections);

        assertThat(result.passed()).isTrue();
        assertThat(check(result, "etl.precheck.resume.ok").detail()).isEqualTo("id");
    }

    @Test
    void aMissingTargetTableIsReportedAsCreatedOnFirstRun() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");

        EtlPrecheckResult result = service.check(form("SELECT id FROM src", "dst",
                EtlWriteMode.OVERWRITE), source, target, connections);

        assertThat(result.passed()).isTrue();
        assertThat(check(result, "etl.precheck.target.missing").detail()).isEqualTo("dst");
        assertThat(codes(result)).doesNotContain("etl.precheck.target.columns.error");
    }

    @Test
    void aTargetMissingAResultColumnIsAnError() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT, name VARCHAR(20))");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");

        EtlPrecheckResult result = service.check(form("SELECT id, name FROM src", "dst",
                EtlWriteMode.APPEND), source, target, connections);

        assertThat(result.passed()).isFalse();
        assertThat(check(result, "etl.precheck.target.columns.error").detail())
                .containsIgnoringCase("NAME");
    }

    @Test
    void anExtraNotNullTargetColumnIsOnlyAWarning() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");
        execute(TARGET_URL,
                "CREATE TABLE dst (id INT, tenant VARCHAR(10) NOT NULL)");

        EtlPrecheckResult result = service.check(form("SELECT id FROM src", "dst",
                EtlWriteMode.APPEND), source, target, connections);

        assertThat(result.passed()).isTrue();
        EtlPrecheckResult.Check warning = check(result, "etl.precheck.target.notNull");
        assertThat(warning.level()).isEqualTo(EtlPrecheckResult.Level.WARN);
        assertThat(warning.detail()).containsIgnoringCase("TENANT");
    }

    @Test
    void aRejectedRangeConditionIsAnErrorAndNothingIsDeleted() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)",
                "INSERT INTO dst VALUES (1), (2)");
        EtlTask form = form("SELECT id FROM src", "dst", EtlWriteMode.RANGE_OVERWRITE);
        form.setRangeCondition("no_such_column = 1");

        EtlPrecheckResult result = service.check(form, source, target, connections);

        assertThat(result.passed()).isFalse();
        assertThat(check(result, "etl.precheck.range.error").detail())
                .containsIgnoringCase("NO_SUCH_COLUMN");
        assertThat(rows()).hasSize(2);
    }

    @Test
    void aValidRangeConditionIsReportedAndNothingIsDeleted() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)",
                "INSERT INTO dst VALUES (1), (2)");
        EtlTask form = form("SELECT id FROM src", "dst", EtlWriteMode.RANGE_OVERWRITE);
        form.setRangeCondition("id > 0");

        EtlPrecheckResult result = service.check(form, source, target, connections);

        assertThat(result.passed()).isTrue();
        assertThat(codes(result)).contains("etl.precheck.range.ok");
        assertThat(rows()).hasSize(2);
    }

    @Test
    void aRangeConditionIsSkippedWhileTheTargetDoesNotExist() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");
        EtlTask form = form("SELECT id FROM src", "dst", EtlWriteMode.RANGE_OVERWRITE);
        form.setRangeCondition("id > 0");

        EtlPrecheckResult result = service.check(form, source, target, connections);

        assertThat(result.passed()).isTrue();
        assertThat(codes(result)).contains("etl.precheck.range.skipped");
    }

    @Test
    void anUnreachableConnectionIsReportedInsteadOfThrowing() throws SQLException {
        EtlConnectionProvider dead = config -> {
            throw new SQLException("Connection refused");
        };

        EtlPrecheckResult result = service.check(form("SELECT 1", "dst",
                EtlWriteMode.APPEND), source, target, dead);

        assertThat(result.passed()).isFalse();
        assertThat(check(result, "etl.precheck.connection.error").detail())
                .containsIgnoringCase("refused");
    }

    // --- configuration validation (public entry point) ---------------------------------

    @Test
    void thePublicEntryRejectsTheSameConnectionOnBothSides() {
        stubConnections(1L, 1L);
        EtlTask form = form("SELECT 1", "dst", EtlWriteMode.APPEND);
        form.setSourceDbId(1L);
        form.setTargetDbId(1L);

        assertThatThrownBy(() -> service.check(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.same.database");
    }

    @Test
    void thePublicEntryRejectsAnInvalidTargetTableName() {
        stubConnections(1L, 2L);
        EtlTask form = form("SELECT 1", "dst; DROP TABLE x", EtlWriteMode.APPEND);
        form.setSourceDbId(1L);
        form.setTargetDbId(2L);

        assertThatThrownBy(() -> service.check(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.targetTable.invalid");
    }

    @Test
    void thePublicEntryRequiresARangeConditionForRangeOverwrite() {
        stubConnections(1L, 2L);
        EtlTask form = form("SELECT 1", "dst", EtlWriteMode.RANGE_OVERWRITE);
        form.setSourceDbId(1L);
        form.setTargetDbId(2L);

        assertThatThrownBy(() -> service.check(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.range.required");
    }

    // --- helpers ------------------------------------------------------------------------

    private void stubConnections(Long sourceId, Long targetId) {
        when(configService.findById(sourceId)).thenReturn(Optional.of(config(sourceId)));
        when(configService.findById(targetId)).thenReturn(Optional.of(config(targetId)));
    }

    private static EtlTask form(String sql, String table, EtlWriteMode mode) {
        EtlTask task = new EtlTask();
        task.setId(7L);
        task.setSourceSql(sql);
        task.setTargetTable(table);
        task.setWriteMode(mode);
        return task;
    }

    private static DatabaseConfig config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.H2);
        return config;
    }

    private static List<String> codes(EtlPrecheckResult result) {
        return result.checks().stream().map(EtlPrecheckResult.Check::code).toList();
    }

    private static EtlPrecheckResult.Check check(EtlPrecheckResult result, String code) {
        return result.checks().stream().filter(c -> code.equals(c.code())).findFirst()
                .orElseThrow(() -> new AssertionError("no check with code " + code
                        + " in " + codes(result)));
    }

    private static void reset(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
        }
    }

    private static void execute(String url, String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private static List<Integer> rows() throws SQLException {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery("SELECT id FROM dst ORDER BY id")) {
            while (rs.next()) {
                ids.add(rs.getInt(1));
            }
        }
        return ids;
    }
}
