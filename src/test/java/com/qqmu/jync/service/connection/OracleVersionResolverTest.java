package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Effective target version for identity-syntax gating.
 *
 * <p>The capability must be the LOWER of the product version and the database's
 * {@code COMPATIBLE} setting: a 19c database still running {@code compatible=11.2} rejects
 * {@code AS IDENTITY}, even though JDBC metadata reports major version 19. When the metadata
 * call fails (some wrapping drivers), the same facts must be recovered from
 * {@code DBMS_UTILITY.DB_VERSION}, which is executable by every account.
 */
class OracleVersionResolverTest {

    private Connection conn;
    private DatabaseMetaData metadata;
    private CallableStatement dbmsCall;
    private Statement probeStatement;
    private final OracleVersionResolver resolver = new OracleVersionResolver();

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        metadata = mock(DatabaseMetaData.class);
        dbmsCall = mock(CallableStatement.class);
        probeStatement = mock(Statement.class);
        when(conn.getMetaData()).thenReturn(metadata);
        when(conn.prepareCall("{ call dbms_utility.db_version(?, ?) }")).thenReturn(dbmsCall);
        when(conn.createStatement()).thenReturn(probeStatement);
    }

    @Test
    void compatibleBelowProductVersionWins() throws SQLException {
        metadataReturns(19);
        dbmsReturns("19.0.0.0.0", "11.2.0.0.0");
        assertThat(resolver.resolveEffectiveMajorVersion(conn)).isEqualTo(11);
    }

    @Test
    void elevenGivesEleven() throws SQLException {
        metadataReturns(11);
        dbmsReturns("11.2.0.4.0", "11.2.0.0.0");
        assertThat(resolver.resolveEffectiveMajorVersion(conn)).isEqualTo(11);
    }

    @Test
    void modernProductAndCompatibleGivesProductMajor() throws SQLException {
        metadataReturns(23);
        dbmsReturns("23.0.0.0.0", "23.6.0");
        assertThat(resolver.resolveEffectiveMajorVersion(conn)).isEqualTo(23);
    }

    @Test
    void metadataFailureIsRecoveredFromDbmsUtility() throws SQLException {
        when(conn.getMetaData()).thenThrow(new SQLException("wrapper refuses metadata"));
        dbmsReturns("19.0.0.0.0", "19.0.0");
        assertThat(resolver.resolveEffectiveMajorVersion(conn)).isEqualTo(19);
    }

    @Test
    void dbmsFailureFallsBackToMetadata() throws SQLException {
        metadataReturns(19);
        when(conn.prepareCall("{ call dbms_utility.db_version(?, ?) }"))
                .thenThrow(new SQLException("not allowed"));
        assertThat(resolver.resolveEffectiveMajorVersion(conn)).isEqualTo(19);
    }

    @Test
    void everythingFailsLeavesVersionUnknown() throws SQLException {
        when(conn.getMetaData()).thenThrow(new SQLException("no metadata"));
        when(conn.prepareCall("{ call dbms_utility.db_version(?, ?) }"))
                .thenThrow(new SQLException("no dbms call"));
        assertThat(resolver.resolveEffectiveMajorVersion(conn)).isNull();
    }

    // --- grammar probe gating ----------------------------------------------------------------

    /**
     * Version numbers can lie: a non-Oracle database reached over the Oracle wire protocol
     * (or an exotic compatibility setup) can report 19 while rejecting Oracle's identity
     * clause. The dialect version must then fall back to the portable emulation.
     */
    @Test
    void modernVersionButParserRejectsIdentityGetsLegacyFallback() throws SQLException {
        metadataReturns(19);
        dbmsReturns("19.0.0.0.0", "19.0.0");
        probeFails(907);
        assertThat(resolver.resolveDialectVersion(conn)).isEqualTo(11);
    }

    @Test
    void twelveOrNewerWithAcceptedGrammarKeepsModernVersion() throws SQLException {
        metadataReturns(23);
        dbmsReturns("23.0.0.0.0", "23.6.0");
        probeOk();
        assertThat(resolver.resolveDialectVersion(conn)).isEqualTo(23);
    }
    /** Pre-12 never runs the probe — the version alone decides. */
    @Test
    void elevenDoesNotProbeAndStaysEleven() throws SQLException {
        metadataReturns(11);
        dbmsReturns("11.2.0.4.0", "11.2.0.0.0");
        assertThat(resolver.resolveDialectVersion(conn)).isEqualTo(11);
        org.mockito.Mockito.verify(conn, org.mockito.Mockito.never()).createStatement();
    }

    /** A wrapping driver that loses the numeric code still gets judged by its ORA- string. */
    @Test
    void probeOraStringWithoutCodeStillRejects() throws SQLException {
        metadataReturns(19);
        dbmsReturns("19.0.0.0.0", "19.0.0");
        when(probeStatement.execute(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new SQLException("bad grammar: ORA-00907: missing right parenthesis"));
        assertThat(resolver.resolveDialectVersion(conn)).isEqualTo(11);
    }

    /** Inability to run the probe is not permission to emit modern syntax. */
    @Test
    void probeInfrastructureFailureFallsBackToLegacy() throws SQLException {
        metadataReturns(19);
        dbmsReturns("19.0.0.0.0", "19.0.0");
        when(probeStatement.execute(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new SQLException("connection lost"));
        assertThat(resolver.resolveDialectVersion(conn)).isEqualTo(11);
    }

    @Test
    void unknownVersionDoesNotProbeAndStaysUnknown() throws SQLException {
        when(conn.getMetaData()).thenThrow(new SQLException("no metadata"));
        when(conn.prepareCall("{ call dbms_utility.db_version(?, ?) }"))
                .thenThrow(new SQLException("no dbms call"));
        assertThat(resolver.resolveDialectVersion(conn)).isNull();
        org.mockito.Mockito.verify(conn, org.mockito.Mockito.never()).createStatement();
    }

    private void metadataReturns(int major) throws SQLException {
        lenient().when(metadata.getDatabaseMajorVersion()).thenReturn(major);
    }

    private void dbmsReturns(String productVersion, String compatible) throws SQLException {
        lenient().when(dbmsCall.getString(1)).thenReturn(productVersion);
        lenient().when(dbmsCall.getString(2)).thenReturn(compatible);
    }

    private void probeOk() throws SQLException {
        // ORA-00942 against the deliberately missing probe table means "grammar parsed,
        // semantics failed" — i.e. identity syntax is accepted.
        lenient().when(probeStatement.execute(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new SQLException("ORA-00942: table or view does not exist", "42000", 942));
    }

    private void probeFails(int errorCode) throws SQLException {
        lenient().when(probeStatement.execute(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new SQLException("ORA-" + errorCode, "42000", errorCode));
    }
}
