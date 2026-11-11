package com.qqmu.jync.service.cdc;

import java.util.List;
import java.util.Locale;

/**
 * Which binlog Query statements change a table's structure.
 *
 * <p>MySQL writes more than table DDL to the binlog as Query events: privileges, account
 * management, maintenance and database creation are statements too. Filing those as
 * skipped-DDL tasks turns every {@code FLUSH PRIVILEGES} into an "action required" row,
 * forces the column projection on, and reports a replication gap that does not exist.
 *
 * <p>The test is a deny-list, not an allow-list: an unrecognised statement is still filed.
 * Missing a real structural change is far worse than filing one maintenance statement, so
 * only prefixes that can never alter a tracked table's columns are excluded.
 */
final class CdcDdlStatements {

    /** Statement prefixes that never change a tracked table's structure. */
    private static final List<String> NON_STRUCTURAL = List.of(
            "GRANT ",
            "REVOKE ",
            "ANALYZE ",
            "OPTIMIZE ",
            "REPAIR ",
            "FLUSH ",
            "CHECK ",
            "CHECKSUM ",
            "CREATE USER ",
            "ALTER USER ",
            "DROP USER ",
            "RENAME USER ",
            "CREATE ROLE ",
            "ALTER ROLE ",
            "DROP ROLE ",
            "SET PASSWORD ",
            "SET DEFAULT ROLE ",
            "SET ROLE ",
            "CREATE DATABASE ",
            "CREATE SCHEMA ",
            "ALTER DATABASE ",
            "ALTER SCHEMA ",
            // A dropped database cannot be represented as a per-table task; the execute
            // dialog would run the statement verbatim on the target connection, where it
            // can delete a same-named database.
            "DROP DATABASE ",
            "DROP SCHEMA ",
            "CREATE TABLESPACE ",
            "ALTER TABLESPACE ",
            "DROP TABLESPACE ",
            "CREATE SERVER ",
            "ALTER SERVER ",
            "DROP SERVER ",
            "INSTALL PLUGIN ",
            "UNINSTALL PLUGIN ",
            "INSTALL COMPONENT ",
            "UNINSTALL COMPONENT ",
            "ALTER INSTANCE ",
            "CREATE LOGFILE GROUP ",
            "ALTER LOGFILE GROUP ",
            "DROP LOGFILE GROUP ");

    private CdcDdlStatements() {
    }

    static boolean changesTableStructure(String sql) {
        String upper = sql == null ? "" : sql.trim().toUpperCase(Locale.ROOT);
        while (upper.endsWith(";")) {
            upper = upper.substring(0, upper.length() - 1).trim();
        }
        for (String prefix : NON_STRUCTURAL) {
            if (upper.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
