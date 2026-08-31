package com.synctool.model;

import lombok.Getter;

/**
 * Supported database products.
 *
 * <p>{@code family} groups products that share a SQL dialect so that a single
 * {@link com.synctool.service.converter.SqlDialect} implementation can serve several
 * products (for example 达梦 is Oracle-compatible, and OpenGauss / 人大金仓 are
 * PostgreSQL-compatible).
 *
 * <p>{@link #CUSTOM} carries no built-in driver or URL template: the user supplies the
 * JDBC URL, driver class name and the path to the driver jar, which is loaded at runtime.
 */
@Getter
public enum DatabaseType {

    /**
     * {@code useAffectedRows=true} is load-bearing, not cosmetic. Connector/J otherwise
     * requests found-rows semantics, under which an {@code ON DUPLICATE KEY UPDATE} that leaves
     * a row exactly as it was still reports one affected row. The sync counts rows changed by
     * reading those update counts, so without this flag a re-scan of an unchanged table would
     * report every row as changed.
     */
    MYSQL("MySQL", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=utf8&useAffectedRows=true",
            3306, DialectFamily.MYSQL),

    MARIADB("MariaDB", "org.mariadb.jdbc.Driver",
            "jdbc:mariadb://%s:%d/%s",
            3306, DialectFamily.MYSQL),

    ORACLE("Oracle", "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@%s:%d:%s",
            1521, DialectFamily.ORACLE),

    SQLSERVER("SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;trustServerCertificate=true",
            1433, DialectFamily.SQLSERVER),

    DB2("DB2", "com.ibm.db2.jcc.DB2Driver",
            "jdbc:db2://%s:%d/%s",
            50000, DialectFamily.DB2),

    POSTGRESQL("PostgreSQL", "org.postgresql.Driver",
            "jdbc:postgresql://%s:%d/%s",
            5432, DialectFamily.POSTGRES),

    OPENGAUSS("OpenGauss", "org.opengauss.Driver",
            "jdbc:opengauss://%s:%d/%s",
            5432, DialectFamily.POSTGRES),

    DM("达梦 DM", "dm.jdbc.driver.DmDriver",
            "jdbc:dm://%s:%d/%s",
            5236, DialectFamily.ORACLE),

    KINGBASE("人大金仓 KingBase", "com.kingbase8.Driver",
            "jdbc:kingbase8://%s:%d/%s",
            54321, DialectFamily.POSTGRES),

    GBASE("南大通用 GBase", "com.gbase.jdbc.Driver",
            "jdbc:gbase://%s:%d/%s",
            5258, DialectFamily.MYSQL),

    OSCAR("神通 Oscar", "com.oscar.Driver",
            "jdbc:oscar://%s:%d/%s",
            2003, DialectFamily.POSTGRES),

    H2("H2", "org.h2.Driver",
            "jdbc:h2:tcp://%s:%d/%s",
            9092, DialectFamily.MYSQL),

    CUSTOM("自定义 Custom", null, null, 0, DialectFamily.GENERIC);

    private final String displayName;
    private final String driverClassName;
    private final String urlTemplate;
    private final int defaultPort;
    private final DialectFamily family;

    DatabaseType(String displayName, String driverClassName, String urlTemplate,
                 int defaultPort, DialectFamily family) {
        this.displayName = displayName;
        this.driverClassName = driverClassName;
        this.urlTemplate = urlTemplate;
        this.defaultPort = defaultPort;
        this.family = family;
    }

    public boolean isCustom() {
        return this == CUSTOM;
    }

    /** Oracle-family products fold unquoted identifiers to upper case. */
    public boolean isUpperCaseIdentifiers() {
        return family == DialectFamily.ORACLE || family == DialectFamily.DB2;
    }

    /**
     * Whether a batched upsert against this target reports enough detail in its JDBC update
     * counts to tell an inserted row from an updated one.
     *
     * <p>MySQL does, and only because {@link #MYSQL}'s URL asks for affected-rows semantics:
     * {@code ON DUPLICATE KEY UPDATE} then answers 1 for an insert, 2 for an update that
     * changed something, and 0 for a row already holding identical values.
     *
     * <p>Every other target here answers 1 for both cases — PostgreSQL's
     * {@code ON CONFLICT DO UPDATE} and the {@code MERGE} statements used by Oracle, SQL Server
     * and DB2 all rewrite the row without saying whether it was already there. MariaDB is
     * excluded deliberately: its driver has its own affected-rows handling that has not been
     * verified here, and guessing wrong would mislabel unchanged rows as inserts, which is
     * worse than declining to classify them.
     */
    public boolean upsertCountsDistinguishInsertFromUpdate() {
        return this == MYSQL;
    }

    public static DatabaseType fromName(String name) {
        if (name == null || name.isBlank()) {
            return CUSTOM;
        }
        for (DatabaseType t : values()) {
            if (t.name().equalsIgnoreCase(name.trim())) {
                return t;
            }
        }
        return CUSTOM;
    }

    /** SQL dialect groupings. Several products map onto one family. */
    public enum DialectFamily {
        MYSQL, ORACLE, POSTGRES, SQLSERVER, DB2, GENERIC
    }
}
