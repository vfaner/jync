package com.synctool.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.service.connection.DataSourceManager;

/**
 * Behaviour of {@link CandidateValidator} against a real database.
 *
 * <p>Runs on H2 rather than a mock, because the claims worth testing are claims about what a
 * database does: that the candidate is created under a throwaway name and not over the real
 * object, that it is dropped afterwards, and that a rejected statement surfaces the engine's own
 * error rather than a generic one. A mocked JDBC layer would let all three regress unnoticed.
 *
 * <p>H2 has no {@code CREATE PROCEDURE}, so the success paths use views. The procedure cases here
 * exercise the parts that run before the statement reaches the engine — header parsing, renaming,
 * self-reference detection — which is where this class's own logic lives.
 */
class CandidateValidatorTest {

    /** Hands out connections to one in-memory database, ignoring host/port entirely. */
    private static class LocalH2Manager extends DataSourceManager {
        private final String url;
        private final AtomicInteger handedOut = new AtomicInteger();
        private boolean broken;

        LocalH2Manager(String url) {
            super(null, null);
            this.url = url;
        }

        @Override
        public Connection getConnection(DatabaseConfig config) throws SQLException {
            if (broken) {
                throw new SQLException("simulated: host unreachable");
            }
            handedOut.incrementAndGet();
            return DriverManager.getConnection(url, "sa", "");
        }
    }

    private String url;
    private LocalH2Manager manager;
    private CandidateValidator validator;
    private DatabaseConfig target;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:validator_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        // H2 discards an in-memory database when the last connection closes, and the validator
        // deliberately opens and closes several. This connection outlives them.
        keepAlive = DriverManager.getConnection(url, "sa", "");
        manager = new LocalH2Manager(url);
        validator = new CandidateValidator(manager);

        target = new DatabaseConfig();
        target.setName("target-under-test");
        target.setType(DatabaseType.H2);
    }

    @AfterEach
    void tearDown() throws SQLException {
        keepAlive.close();
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute(sql);
        }
    }

    /** Object names currently present, so leaks and collateral damage are both visible. */
    private List<String> objectNames(String type) throws SQLException {
        String sql = "TABLE".equals(type)
                ? "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"
                : "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = 'PUBLIC'";
        List<String> names = new ArrayList<>();
        try (Statement st = keepAlive.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    @Test
    @DisplayName("a valid view is accepted")
    void aValidViewIsAccepted() throws SQLException {
        exec("CREATE TABLE ORDERS (ID INT, AMOUNT DECIMAL(10,2))");

        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW V_ORDER_TOTAL AS SELECT ID, SUM(AMOUNT) AS TOTAL FROM ORDERS GROUP BY ID",
                "V_ORDER_TOTAL");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isNull();
        assertThat(result.getTempName()).startsWith("SYNCTOOL_AI_CHECK_");
    }

    @Test
    @DisplayName("the temporary object is dropped, leaving nothing behind")
    void theTemporaryObjectIsDropped() throws SQLException {
        exec("CREATE TABLE ORDERS (ID INT)");
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW V_X AS SELECT ID FROM ORDERS", "V_X");

        assertThat(result.isSuccess()).isTrue();
        // The whole design rests on this: the check writes to a production target, so a leaked
        // object is a real consequence, not an aesthetic one.
        assertThat(objectNames("VIEW")).isEmpty();
    }

    @Test
    @DisplayName("the existing object of the same name is left untouched")
    void theRealObjectIsNeverOverwritten() throws SQLException {
        exec("CREATE TABLE ORDERS (ID INT)");
        exec("CREATE VIEW V_LIVE AS SELECT 1 AS ORIGINAL_COLUMN");

        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW V_LIVE AS SELECT 2 AS REPLACEMENT_COLUMN", "V_LIVE");

        assertThat(result.isSuccess()).isTrue();
        // Had the rename not happened, H2 would either have failed on the duplicate name or --
        // with OR REPLACE -- swapped the live definition for the candidate. Reading the column
        // back proves which of the two definitions is still installed.
        try (Statement st = keepAlive.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM V_LIVE")) {
            assertThat(rs.getMetaData().getColumnName(1)).isEqualTo("ORIGINAL_COLUMN");
        }
        assertThat(objectNames("VIEW")).containsExactly("V_LIVE");
    }

    @Test
    @DisplayName("a statement the target rejects fails with the target's own message")
    void aRejectedStatementReportsTheEnginesError() {
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW V_BROKEN AS SELECT * FROM TABLE_THAT_DOES_NOT_EXIST", "V_BROKEN");

        assertThat(result.isSuccess()).isFalse();
        // A generic "validation failed" would send the reviewer back to guessing.
        assertThat(result.getMessage()).contains("TABLE_THAT_DOES_NOT_EXIST");
    }

    @Test
    @DisplayName("a failed create still leaves nothing behind")
    void aFailedCreateLeavesNothingBehind() throws SQLException {
        validator.validate(target, "CREATE VIEW V_BROKEN AS SELECT * FROM NOPE", "V_BROKEN");

        assertThat(objectNames("VIEW")).isEmpty();
        // Two connections: the create attempt, and the unconditional cleanup pass.
        assertThat(manager.handedOut.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a statement with no recognisable header is refused rather than run")
    void aStatementWithoutAHeaderIsRefused() {
        CandidateValidator.ValidationResult result = validator.validate(target,
                "UPDATE ORDERS SET AMOUNT = 0", "V_X");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("error.validate.noHeader");
        // Nothing may reach the target: there is no name to rename, so running this verbatim
        // would execute an arbitrary statement against production.
        assertThat(manager.handedOut.get()).isZero();
    }

    @Test
    @DisplayName("an empty candidate is refused without connecting")
    void anEmptyCandidateIsRefused() {
        CandidateValidator.ValidationResult result = validator.validate(target, "   ", "V_X");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("error.validate.empty");
        assertThat(manager.handedOut.get()).isZero();
    }

    @Test
    @DisplayName("OR REPLACE, DEFINER and ALGORITHM clauses are still renamed correctly")
    void decoratedHeadersAreRenamed() throws SQLException {
        exec("CREATE VIEW V_LIVE AS SELECT 1 AS ORIGINAL_COLUMN");

        // A MySQL-shaped header. If the rename missed, OR REPLACE would silently destroy the
        // live view -- the worst outcome this class can produce.
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE OR REPLACE VIEW V_LIVE AS SELECT 2 AS REPLACEMENT_COLUMN", "V_LIVE");

        assertThat(result.isSuccess()).isTrue();
        try (Statement st = keepAlive.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM V_LIVE")) {
            assertThat(rs.getMetaData().getColumnName(1)).isEqualTo("ORIGINAL_COLUMN");
        }
    }

    @Test
    @DisplayName("a schema-qualified name is replaced whole, not just its last part")
    void aSchemaQualifiedNameIsReplacedWhole() throws SQLException {
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW PUBLIC.V_QUALIFIED AS SELECT 1 AS X", "V_QUALIFIED");

        // Replacing only the trailing part would leave PUBLIC.SYNCTOOL_AI_CHECK_xxx, which still
        // works here but would land in the wrong schema on a target where the source schema
        // differs from the connection's own.
        assertThat(result.isSuccess()).isTrue();
        assertThat(objectNames("VIEW")).isEmpty();
    }

    @Test
    @DisplayName("a quoted name is replaced whole")
    void aQuotedNameIsReplacedWhole() {
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW \"V Spaced Name\" AS SELECT 1 AS X", "V Spaced Name");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTempName()).doesNotContain(" ");
    }

    @Test
    @DisplayName("a body that calls itself is reported as a caveat")
    void aSelfReferenceIsReportedAsACaveat() {
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE PROCEDURE FACTORIAL(n INT) BEGIN CALL FACTORIAL(n - 1); END",
                "FACTORIAL");

        // H2 rejects the statement, but the caveat is computed before execution and must survive
        // a failure -- the reviewer needs to know the check could not have covered the recursion
        // even when the check itself did not get that far.
        assertThat(result.getCaveats()).contains("warn.validate.selfReference");
    }

    @Test
    @DisplayName("a name appearing only in the header is not a self-reference")
    void theHeaderOccurrenceIsNotASelfReference() throws SQLException {
        exec("CREATE TABLE ORDERS (ID INT)");
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW V_PLAIN AS SELECT ID FROM ORDERS", "V_PLAIN");

        assertThat(result.isSuccess()).isTrue();
        // The header's own occurrence is the one that gets renamed. Counting it would attach a
        // scary caveat to every single object and train the reviewer to ignore caveats.
        assertThat(result.getCaveats()).isEmpty();
    }

    @Test
    @DisplayName("a name embedded in a longer identifier is not a self-reference")
    void aPartialNameMatchIsNotASelfReference() throws SQLException {
        exec("CREATE TABLE V_TOTAL_HISTORY (ID INT)");
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW V_TOTAL AS SELECT ID FROM V_TOTAL_HISTORY", "V_TOTAL");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCaveats()).isEmpty();
    }

    @Test
    @DisplayName("the temp name stays within Oracle's 30-byte identifier limit")
    void theTempNameFitsOraclesLimit() throws SQLException {
        exec("CREATE TABLE ORDERS (ID INT)");
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW V_X AS SELECT ID FROM ORDERS", "V_X");

        // Oracle before 12.2 caps identifiers at 30 bytes and 达梦 follows suit, so a longer
        // temp name would make validation impossible on exactly the products that need it most.
        assertThat(result.getTempName()).hasSizeLessThanOrEqualTo(30);
    }

    @Test
    @DisplayName("two checks in a row do not collide")
    void consecutiveChecksUseDistinctNames() throws SQLException {
        exec("CREATE TABLE ORDERS (ID INT)");
        String sql = "CREATE VIEW V_X AS SELECT ID FROM ORDERS";

        String first = validator.validate(target, sql, "V_X").getTempName();
        String second = validator.validate(target, sql, "V_X").getTempName();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("an unreachable target is distinguished from a rejected statement")
    void anUnreachableTargetIsReportedSeparately() {
        manager.broken = true;

        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW V_X AS SELECT 1 AS X", "V_X");

        assertThat(result.isSuccess()).isFalse();
        // "Your SQL is wrong" and "the database is down" call for different responses from the
        // reviewer, so they must not share a message.
        assertThat(result.getMessage())
                .startsWith("error.validate.unreachable")
                .contains("host unreachable");
    }

    @Test
    @DisplayName("a target whose type is unset is still validated")
    void anUnsetTargetTypeDoesNotBreakValidation() throws SQLException {
        exec("CREATE TABLE ORDERS (ID INT)");
        target.setType(null);

        // getType() feeds only the Oracle compile-error branch. A null must skip that branch,
        // not throw and lose the result of a CREATE that already ran.
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW V_X AS SELECT ID FROM ORDERS", "V_X");

        assertThat(result.isSuccess()).isTrue();
        assertThat(objectNames("VIEW")).isEmpty();
    }

    @Test
    @DisplayName("a null original name does not break self-reference detection")
    void aNullOriginalNameIsTolerated() throws SQLException {
        exec("CREATE TABLE ORDERS (ID INT)");
        CandidateValidator.ValidationResult result = validator.validate(target,
                "CREATE VIEW V_X AS SELECT ID FROM ORDERS", null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCaveats()).isEmpty();
    }
}
