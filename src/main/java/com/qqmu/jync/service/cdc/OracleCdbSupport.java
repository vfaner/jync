package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import lombok.extern.slf4j.Slf4j;

/**
 * P0-2: Oracle multitenant (CDB) detection for the LogMiner path.
 *
 * <p>On a CDB, DBMS_LOGMNR may only run in CDB$ROOT (ORA-65040 anywhere else), so the
 * consumer needs to know three things the plain connection does not say: whether the
 * instance is a CDB at all, which container this connection landed in, and the CDB name
 * (which doubles as the root service name by Oracle convention — gvenzl/oracle-free
 * ships FREE / FREEPDB1).
 *
 * <p>Every helper degrades honestly: a denied query returns null/-1 ("indeterminate"),
 * never a guessed default — guessing non-CDB on a real CDB defers the failure to an
 * ORA-65040 at first mine, far from the form that could fix it.
 */
@Slf4j
final class OracleCdbSupport {

    private OracleCdbSupport() { }

    /** @return TRUE/FALSE from V$DATABASE.CDB, or null when the query cannot run. */
    static Boolean isCdb(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT CDB FROM V$DATABASE")) {
            if (!rs.next()) {
                return null;
            }
            return "YES".equalsIgnoreCase(rs.getString(1));
        } catch (SQLException e) {
            log.debug("V$DATABASE.CDB unreadable (pre-12c or missing grant): {}", e.getMessage());
            return null;
        }
    }

    /** @return V$DATABASE.NAME — the CDB name, i.e. the root service name; null if unreadable. */
    static String cdbName(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT NAME FROM V$DATABASE")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            log.debug("V$DATABASE.NAME unreadable: {}", e.getMessage());
            return null;
        }
    }

    /** @return SYS_CONTEXT CON_ID of this connection's container; -1 when unreadable. */
    static int currentConId(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT SYS_CONTEXT('USERENV', 'CON_ID') FROM DUAL")) {
            if (!rs.next()) {
                return -1;
            }
            try {
                return Integer.parseInt(rs.getString(1).trim());
            } catch (RuntimeException parse) {
                return -1;
            }
        } catch (SQLException e) {
            log.debug("CON_ID unreadable: {}", e.getMessage());
            return -1;
        }
    }

    /** Root-service JDBC URL for a CDB on the same host/port as the configured source. */
    static String rootServiceUrl(String host, int port, String cdbName) {
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + cdbName;
    }
}
