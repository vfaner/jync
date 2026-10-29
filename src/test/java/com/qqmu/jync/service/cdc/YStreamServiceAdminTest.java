package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The source-side YStream service, exercised through mock JDBC so the exact statements are
 * pinned. Two of them were measured the hard way: {@code ADD_TABLES} takes exactly three
 * string arguments (a 2-arg call is {@code YAS-04323}), and {@code DROP} refuses a service
 * that is not running — {@code STOP} on a {@code CREATED} service is itself
 * {@code YAS-02731}, so the release path has to look at {@code STATUS} first.
 */
class YStreamServiceAdminTest {

    @Test
    void theServiceNameCarriesTheProjectIdAndAToken() {
        String name = YStreamServiceAdmin.serviceName(42L, "1A2B3C4D");

        assertThat(name).isEqualTo("JYNC_42_1A2B3C4D");
        assertThat(YStreamServiceAdmin.serviceFor(name)).isEqualTo(42L);
    }

    @Test
    void theGeneratedTokenIsEightUppercaseHexCharacters() {
        for (int i = 0; i < 50; i++) {
            assertThat(YStreamServiceAdmin.newToken()).matches("[0-9A-F]{8}");
        }
    }

    @Test
    void aNameThatIsNotOursHasNoProject() {
        assertThat(YStreamServiceAdmin.serviceFor("YSTREAM_OTHER")).isNull();
        assertThat(YStreamServiceAdmin.serviceFor("JYNC_42")).isNull();
        assertThat(YStreamServiceAdmin.serviceFor("JYNC_x_1A2B3C4D")).isNull();
        assertThat(YStreamServiceAdmin.serviceFor(null)).isNull();
    }

    @Test
    void servicesForKeepsOnlyThisProjectsServicesAndParsesTheView() throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getLong("SERVER_ID")).thenReturn(0L, 1L);
        when(rs.getString("SERVER_NAME"))
                .thenReturn("JYNC_42_1A2B3C4D", "JYNC_7_DEADBEEF");
        when(rs.getString("STATUS")).thenReturn("STARTED", "RUNNING");
        when(rs.getLong("START_SCN")).thenReturn(882304965853585408L, 1L);
        when(rs.getString("RESTART_POSITION"))
                .thenReturn("882304965853585408-0-6806-0-0", null);
        when(rs.getString("ERROR")).thenReturn(null, "boom");

        List<YStreamServiceAdmin.Service> mine = YStreamServiceAdmin.servicesFor(conn, 42L);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).name()).isEqualTo("JYNC_42_1A2B3C4D");
        assertThat(mine.get(0).status()).isEqualTo("STARTED");
        assertThat(mine.get(0).startScn()).isEqualTo(882304965853585408L);
        assertThat(mine.get(0).restartPosition()).isEqualTo("882304965853585408-0-6806-0-0");
    }

    @Test
    void addTablesQuotesEveryNameSoLowerCaseTablesAreNotFolded() throws Exception {
        // Measured: DBA_YSTREAM_TABLES keeps the exact dictionary spelling. An unquoted
        // lowercase table would be folded to upper case and silently capture nothing.
        Connection conn = mock(Connection.class);
        CallableStatement cs = mock(CallableStatement.class);
        when(conn.prepareCall("{call DBMS_YSTREAM_ADM.ADD_TABLES(?, ?, ?)}")).thenReturn(cs);

        YStreamServiceAdmin.addTables(conn, "JYNC_42_1A2B3C4D",
                List.of("SYS.T1", "sys.ys_lower"));

        verify(cs).setString(1, "JYNC_42_1A2B3C4D");
        verify(cs).setString(2, "\"SYS\".\"T1\",\"sys\".\"ys_lower\"");
        verify(cs).setString(3, "");
        verify(cs).execute();
    }

    @Test
    void addTablesBatchesFiftyPerCall() throws Exception {
        Connection conn = mock(Connection.class);
        CallableStatement cs = mock(CallableStatement.class);
        when(conn.prepareCall("{call DBMS_YSTREAM_ADM.ADD_TABLES(?, ?, ?)}")).thenReturn(cs);
        List<String> tables = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            tables.add("SYS.T" + i);
        }

        YStreamServiceAdmin.addTables(conn, "JYNC_42_1A2B3C4D", tables);

        verify(cs, times(3)).execute();
        org.mockito.ArgumentCaptor<String> lists =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cs, times(3)).setString(eq(2), lists.capture());
        assertThat(lists.getAllValues()).extracting(list -> list.split(",").length)
                .containsExactly(50, 50, 20);
    }

    @Test
    void addTablesWithNothingToAddIssuesNoStatement() throws Exception {
        Connection conn = mock(Connection.class);

        YStreamServiceAdmin.addTables(conn, "JYNC_42_1A2B3C4D", List.of());

        verify(conn, never()).prepareCall(anyString());
    }

    @Test
    void createAndStartArePlainCalls() throws Exception {
        Connection conn = mock(Connection.class);
        CallableStatement create = mock(CallableStatement.class);
        CallableStatement start = mock(CallableStatement.class);
        when(conn.prepareCall("{call DBMS_YSTREAM_ADM.CREATE(?)}")).thenReturn(create);
        when(conn.prepareCall("{call DBMS_YSTREAM_ADM.START(?)}")).thenReturn(start);

        YStreamServiceAdmin.createService(conn, "JYNC_42_1A2B3C4D");
        YStreamServiceAdmin.startService(conn, "JYNC_42_1A2B3C4D");

        verify(create).setString(1, "JYNC_42_1A2B3C4D");
        verify(create).execute();
        verify(start).setString(1, "JYNC_42_1A2B3C4D");
        verify(start).execute();
    }

    @Test
    void droppingAStartedServiceStopsItFirst() throws Exception {
        Connection conn = mock(Connection.class);
        CallableStatement stop = mock(CallableStatement.class);
        CallableStatement drop = mock(CallableStatement.class);
        when(conn.prepareCall("{call DBMS_YSTREAM_ADM.STOP(?)}")).thenReturn(stop);
        when(conn.prepareCall("{call DBMS_YSTREAM_ADM.DROP(?)}")).thenReturn(drop);

        YStreamServiceAdmin.dropService(conn, new YStreamServiceAdmin.Service(
                0L, "JYNC_42_1A2B3C4D", "STARTED", 1L, null, null));

        verify(stop).execute();
        verify(drop).execute();
    }

    @Test
    void droppingACreatedServiceSkipsStopBecauseStopWouldFail() throws Exception {
        // Measured: STOP on a CREATED service is YAS-02731 "status is invalid".
        Connection conn = mock(Connection.class);
        CallableStatement drop = mock(CallableStatement.class);
        when(conn.prepareCall("{call DBMS_YSTREAM_ADM.DROP(?)}")).thenReturn(drop);

        YStreamServiceAdmin.dropService(conn, new YStreamServiceAdmin.Service(
                0L, "JYNC_42_1A2B3C4D", "CREATED", 1L, null, null));

        verify(conn, never()).prepareCall("{call DBMS_YSTREAM_ADM.STOP(?)}");
        verify(drop).execute();
    }

    @Test
    void capturedTablesIsASetOfSchemaDotTable() throws Exception {
        Connection conn = mock(Connection.class);
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("SCHEMA")).thenReturn("SYS", "SYS");
        when(rs.getString("TABLE_NAME")).thenReturn("T1", "T2");

        Set<String> tables = YStreamServiceAdmin.capturedTables(conn, "JYNC_42_1A2B3C4D");

        assertThat(tables).containsExactly("SYS.T1", "SYS.T2");
    }

    @Test
    void theServiceCountReadsTheWholeInstance() throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(31);

        assertThat(YStreamServiceAdmin.serviceCount(conn)).isEqualTo(31);
    }

    @Test
    void releaseSwallowsAConnectionFailureButStillTriesEveryService() throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getLong("SERVER_ID")).thenReturn(0L, 1L);
        when(rs.getString("SERVER_NAME")).thenReturn(
                "JYNC_42_1A2B3C4D", "JYNC_42_DEADBEEF");
        when(rs.getString("STATUS")).thenReturn("STARTED", "CREATED");
        when(conn.prepareCall(anyString())).thenThrow(new SQLException("gone"));

        // Never throws: deleting a project must not fail because the source is unreachable.
        YStreamServiceAdmin.releaseQuietly(conn, 42L);

        verify(conn, times(1)).prepareCall("{call DBMS_YSTREAM_ADM.STOP(?)}");
        verify(conn, times(2)).prepareCall("{call DBMS_YSTREAM_ADM.DROP(?)}");
    }

    @Test
    void aConnectionThatIsAlreadyGoneStillDoesNotThrow() {
        assertThatCode(() -> YStreamServiceAdmin.releaseQuietly(null, 42L))
                .doesNotThrowAnyException();
    }
}
