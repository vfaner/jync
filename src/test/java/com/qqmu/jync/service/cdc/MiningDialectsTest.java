package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * Dialect selection is a capability probe, never a product name: the project's rule is
 * "probe, don't guess" (same model as {@code PgLogicalDialect.detectLive}).
 */
class MiningDialectsTest {

    private static final class Fixture {
        final Connection conn = mock(Connection.class);
        final Statement st = mock(Statement.class);
        final ResultSet ok = mock(ResultSet.class);

        Fixture() throws SQLException {
            when(conn.createStatement()).thenReturn(st);
            when(ok.next()).thenReturn(true);
        }

        void supports(String query) throws SQLException {
            when(st.executeQuery(query)).thenReturn(ok);
        }

        void rejects(String query) throws SQLException {
            when(st.executeQuery(query)).thenThrow(new SQLException("not this product"));
        }
    }

    @Test
    void oracleIsDetectedFirstWhenBothProbesWouldAnswer() throws SQLException {
        // A DM instance has no V$DATABASE.CURRENT_SCN, but probing Oracle first keeps the
        // existing Oracle path byte-identical to what shipped in v1.6.0.
        Fixture fx = new Fixture();
        fx.supports(OracleLogMinerDialect.Q_VERIFY);
        fx.supports(DmLogMinerDialect.Q_VERIFY);

        assertThat(MiningDialects.detect(fx.conn)).isSameAs(OracleLogMinerDialect.INSTANCE);
    }

    @Test
    void dmIsSelectedWhenTheOracleProbeFails() throws SQLException {
        Fixture fx = new Fixture();
        fx.rejects(OracleLogMinerDialect.Q_VERIFY);
        fx.supports(DmLogMinerDialect.Q_VERIFY);

        assertThat(MiningDialects.detect(fx.conn)).isSameAs(DmLogMinerDialect.INSTANCE);
    }

    @Test
    void anUnrecognisedSourceFailsNamingEveryProbeThatWasTried() throws SQLException {
        Fixture fx = new Fixture();
        fx.rejects(OracleLogMinerDialect.Q_VERIFY);
        fx.rejects(DmLogMinerDialect.Q_VERIFY);

        try {
            MiningDialects.detect(fx.conn);
            org.assertj.core.api.Assertions.fail("expected SQLException");
        } catch (SQLException e) {
            assertThat(e.getMessage())
                    .contains("CURRENT_SCN")
                    .contains("V$RLOGFILE");
        }
    }
}
