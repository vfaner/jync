package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Picks the LogMiner dialect by capability probe, never by product name — the same model the
 * binlog and PG-logical paths use. Oracle is tried first so an Oracle source takes exactly
 * the code path v1.6.0 shipped.
 */
final class MiningDialects {

    private static final List<MiningDialect> CANDIDATES =
            List.of(OracleLogMinerDialect.INSTANCE, DmLogMinerDialect.INSTANCE);

    private MiningDialects() {
    }

    static MiningDialect detect(Connection source) throws SQLException {
        SQLException lastRejection = null;
        for (MiningDialect candidate : CANDIDATES) {
            try {
                candidate.verify(source);
                return candidate;
            } catch (SQLException rejected) {
                // Not this product; try the next probe. The last server error rides along
                // as the cause so a DBA sees the real reason, not just "no dialect".
                lastRejection = rejected;
            }
        }
        throw new SQLException("Cannot determine the LogMiner dialect of this source:"
                + " Oracle probe (CURRENT_SCN from V$DATABASE) and DM probe"
                + " (SELECT COUNT(*) FROM V$RLOGFILE) both failed. LOGMINER_CDC needs a"
                + " source whose redo can be read through DBMS_LOGMNR.", lastRejection);
    }
}
