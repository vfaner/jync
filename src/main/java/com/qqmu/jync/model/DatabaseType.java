package com.qqmu.jync.model;

import lombok.Getter;

/**
 * Supported database products.
 *
 * <p>{@code family} groups products that share a SQL dialect so that a single
 * {@link com.qqmu.jync.service.converter.SqlDialect} implementation can serve several
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

    /**
     * Service-name syntax ({@code @//host:port/service}), not the old colon-SID form
     * ({@code @host:port:SID}): the listener registers services, and under 12c+ CDB/PDB a
     * pluggable database has no SID at all, so the colon form dies with ORA-12505
     * ("listener does not currently know of SID") even when host and port are right.
     * Non-CDB instances register a service equal to the database name, so the same
     * "database name" field keeps working for them; truly SID-only setups can still
     * paste a full descriptor into the custom-URL field.
     */
    ORACLE("Oracle", "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@//%s:%d/%s",
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

    /**
     * MySQL-mode tenants. The same driver reaches Oracle-mode tenants, but the dialect
     * does not follow, so an Oracle-mode deployment belongs in a CUSTOM connection with
     * an Oracle-family URL rather than this preset. Default port is the OBProxy port;
     * a direct observer connection uses 2881.
     */
    OCEANBASE("OceanBase", "com.oceanbase.jdbc.Driver",
            "jdbc:oceanbase://%s:%d/%s",
            2883, DialectFamily.MYSQL),

    /**
     * Speaks the MySQL protocol and its vendor points users at the standard Connector/J,
     * so this preset reuses the bundled MySQL driver instead of the third-party
     * repackaged "TiDB driver" that circulates on Maven Central.
     */
    TIDB("TiDB", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=utf8&useAffectedRows=true",
            4000, DialectFamily.MYSQL),

    HIGHGO("瀚高 HighGo", "com.highgo.jdbc.Driver",
            "jdbc:highgo://%s:%d/%s",
            5866, DialectFamily.POSTGRES),

    VASTBASE("海量 Vastbase", "cn.com.vastbase.Driver",
            "jdbc:vastbase://%s:%d/%s",
            5432, DialectFamily.POSTGRES),

    /**
     * Oracle-compatible: unquoted identifiers fold to upper case and MERGE upserts apply,
     * so it joins the Oracle dialect family. The URL prefix is {@code jdbc:yasdb:},
     * not {@code jdbc:yashandb:} — verified against the driver itself.
     */
    YASHANDB("崖山 YashanDB", "com.yashandb.jdbc.Driver",
            "jdbc:yasdb://%s:%d/%s",
            1688, DialectFamily.ORACLE),

    GBASE("南大通用 GBase", "com.gbase.jdbc.Driver",
            "jdbc:gbase://%s:%d/%s",
            5258, DialectFamily.MYSQL),

    OSCAR("神通 Oscar", "com.oscar.Driver",
            "jdbc:oscar://%s:%d/%s",
            2003, DialectFamily.POSTGRES),

    /**
     * GENERIC, not MYSQL: H2 only understands MySQL syntax when explicitly started with
     * {@code MODE=MySQL}, and the MySQL dialect's DDL suffix
     * ({@code ENGINE=InnoDB DEFAULT CHARSET=utf8mb4}) is rejected by a default-mode H2.
     * The generic dialect emits standard SQL that H2 accepts in any mode.
     */
    H2("H2", "org.h2.Driver",
            "jdbc:h2:tcp://%s:%d/%s",
            9092, DialectFamily.GENERIC),

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
