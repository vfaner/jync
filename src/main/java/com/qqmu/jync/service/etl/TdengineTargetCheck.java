package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Asks the TDengine dictionary whether a target names a super table.
 *
 * <p>A super table accepts {@code SELECT} but refuses {@code INSERT} without a {@code tbname},
 * so a run that treats it as an ordinary target would fail after the pre-SQL and the
 * write-mode DELETE had already run.
 */
final class TdengineTargetCheck {

    private TdengineTargetCheck() {
    }

    static boolean isSuperTable(Connection conn, String qualifiedTable) throws SQLException {
        String table = qualifiedTable.trim();
        int dot = table.lastIndexOf('.');
        String database = dot > 0 ? table.substring(0, dot) : null;
        String name = dot > 0 ? table.substring(dot + 1) : table;
        String sql = "SELECT stable_name FROM information_schema.ins_stables"
                + " WHERE stable_name = '" + escape(name) + "'"
                + (database != null
                        ? " AND db_name = '" + escape(database) + "'"
                        : " AND db_name = DATABASE()");
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next();
        }
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }
}
