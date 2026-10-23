package com.qqmu.jync.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

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
     *
     * <p>{@code rewriteBatchedStatements=true} is what makes {@code executeBatch()} one network
     * round trip instead of one per row — Connector/J sends each statement of a batch on its own
     * unless told to rewrite it. Every write this product makes to MySQL is an
     * {@code INSERT ... ON DUPLICATE KEY UPDATE}, a shape the rewrite handles, so a first full
     * load would otherwise be capped by round-trip latency no matter how large its batch is.
     * A rewritten batch travels as one packet: keep the full-load batch size moderate on
     * servers with a small {@code max_allowed_packet} (MySQL 5.7 defaults to 4 MB).
     */
    MYSQL("db.type.MYSQL", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=utf8&useAffectedRows=true&rewriteBatchedStatements=true",
            3306, DialectFamily.MYSQL),

    MARIADB("db.type.MARIADB", "org.mariadb.jdbc.Driver",
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
    ORACLE("db.type.ORACLE", "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@//%s:%d/%s",
            1521, DialectFamily.ORACLE),

    /**
     * SQL Server is the one preset whose port may be left blank. A named instance
     * ({@code host\INSTANCE}, or the {@code instanceName} property) is resolved through the
     * SQL Browser service and has no fixed port; substituting the default-instance port
     * 1433 makes every such connection time out. The portless template is used only when
     * the user clears the port field — an explicit port still wins.
     */
    SQLSERVER("db.type.SQLSERVER", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;trustServerCertificate=true",
            "jdbc:sqlserver://%s;databaseName=%s;encrypt=false;trustServerCertificate=true",
            1433, DialectFamily.SQLSERVER),

    DB2("db.type.DB2", "com.ibm.db2.jcc.DB2Driver",
            "jdbc:db2://%s:%d/%s",
            50000, DialectFamily.DB2),

    POSTGRESQL("db.type.POSTGRESQL", "org.postgresql.Driver",
            "jdbc:postgresql://%s:%d/%s",
            5432, DialectFamily.POSTGRES),

    OPENGAUSS("db.type.OPENGAUSS", "org.opengauss.Driver",
            "jdbc:opengauss://%s:%d/%s",
            5432, DialectFamily.POSTGRES),

    /**
     * Huawei's commercial GaussDB, which is not openGauss under another name: it ships its
     * own driver ({@code gaussdbjdbc.jar}) and URL prefix. Connecting it as CUSTOM would
     * place it in the GENERIC family, losing the PostgreSQL type mapping and metadata
     * reader, so it gets a preset of its own. The driver is not redistributable, hence the
     * external-jar path. The default port is the usual GaussDB instance port; deployments
     * with a different one edit the field.
     */
    GAUSSDB("db.type.GAUSSDB", "com.huawei.gaussdb.jdbc.Driver",
            "jdbc:gaussdb://%s:%d/%s",
            8000, DialectFamily.POSTGRES),

    DM("db.type.DM", "dm.jdbc.driver.DmDriver",
            "jdbc:dm://%s:%d/%s",
            5236, DialectFamily.ORACLE),

    KINGBASE("db.type.KINGBASE", "com.kingbase8.Driver",
            "jdbc:kingbase8://%s:%d/%s",
            54321, DialectFamily.POSTGRES),

    /**
     * MySQL-mode tenants. The same driver reaches Oracle-mode tenants, but the dialect
     * does not follow, so an Oracle-mode deployment belongs in a CUSTOM connection with
     * an Oracle-family URL rather than this preset. Default port is the OBProxy port;
     * a direct observer connection uses 2881.
     */
    OCEANBASE("db.type.OCEANBASE", "com.oceanbase.jdbc.Driver",
            "jdbc:oceanbase://%s:%d/%s",
            2883, DialectFamily.MYSQL),

    /**
     * Speaks the MySQL protocol and its vendor points users at the standard Connector/J,
     * so this preset reuses the bundled MySQL driver instead of the third-party
     * repackaged "TiDB driver" that circulates on Maven Central.
     */
    TIDB("db.type.TIDB", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=utf8&useAffectedRows=true&rewriteBatchedStatements=true",
            4000, DialectFamily.MYSQL),

    /**
     * ZTE GoldenDB speaks the MySQL protocol, and ZTE's own connection examples use the
     * standard Connector/J with a {@code jdbc:mysql://} URL, so this preset reuses the
     * bundled MySQL driver. The default port is the compute node / DBProxy port that
     * applications connect to in a distributed deployment; single-node deployments and
     * proxies mapped to 3306 just edit the port. A deployment that mandates the vendor's
     * own driver ({@code com.goldendb.jdbc.Driver}, {@code jdbc:goldendb://}) keeps this
     * preset: switch on the vendor-driver option on the connection form and fill in the
     * URL, jar path and driver class.
     */
    GOLDENDB("db.type.GOLDENDB", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=utf8&useAffectedRows=true&rewriteBatchedStatements=true",
            8880, DialectFamily.MYSQL),

    /**
     * Tencent Cloud TDSQL, <b>MySQL edition</b>: the vended protocol is MySQL, so the
     * bundled Connector/J connects it and the MYSQL family supplies dialect and metadata.
     * The console may assign a non-default port (53306 is common); edit the port field.
     * TDSQL's PostgreSQL edition belongs to the POSTGRESQL preset — one preset cannot cover
     * two protocols without failing the connection with a protocol error.
     */
    TDSQL("db.type.TDSQL", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=utf8&useAffectedRows=true&rewriteBatchedStatements=true",
            3306, DialectFamily.MYSQL),

    /**
     * Alibaba Cloud PolarDB, <b>MySQL edition</b>: same reasoning as {@link #TDSQL}.
     * PolarDB's PostgreSQL edition uses the POSTGRESQL preset; the Oracle-compatible
     * edition is a PostgreSQL-protocol product with its own driver
     * ({@code com.aliyun.polardb2.Driver}) and belongs in CUSTOM.
     */
    POLARDB("db.type.POLARDB", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=utf8&useAffectedRows=true&rewriteBatchedStatements=true",
            3306, DialectFamily.MYSQL),

    /**
     * 万里 GreatDB states full compatibility with the native MySQL access interface, and its
     * own development guide connects with Connector/J, so this preset reuses the bundled
     * MySQL driver. The vendor driver ({@code com.greatdb.cj.jdbc.Driver},
     * {@code jdbc:greatdb://}) is supplied through the form's vendor-driver option; GM/TLS
     * deployments that also need extra URL parameters stay in this preset because the URL
     * field accepts them.
     */
    GREATDB("db.type.GREATDB", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=utf8&useAffectedRows=true&rewriteBatchedStatements=true",
            3306, DialectFamily.MYSQL),

    /**
     * UnionPay UPDRDB is compatible with MySQL 5.7 protocol; its documentation connects an
     * instance with any generic MySQL client or JDBC driver, so the bundled Connector/J and
     * the MYSQL family apply unchanged.
     */
    UPDRDB("db.type.UPDRDB", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=utf8&useAffectedRows=true&rewriteBatchedStatements=true",
            3306, DialectFamily.MYSQL),

    /**
     * Transwarp KunDB exposes a MySQL-protocol mode, which is the only mode this preset
     * covers; an Oracle-mode deployment uses CUSTOM with an Oracle-family URL. The JDBC
     * port depends on the deployment (9669 is the documented JDBC default, 2.1.x behind
     * KunGate commonly listens on 15307), so the form's port field is expected to be edited.
     */
    KUNDB("db.type.KUNDB", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&characterEncoding=utf8&useAffectedRows=true&rewriteBatchedStatements=true",
            9669, DialectFamily.MYSQL),

    /**
     * Ping An RASESQL is fully compatible with PostgreSQL 13/15, so the bundled PostgreSQL
     * driver, dialect and metadata reader apply unchanged.
     */
    RASESQL("db.type.RASESQL", "org.postgresql.Driver",
            "jdbc:postgresql://%s:%d/%s",
            5432, DialectFamily.POSTGRES),

    /**
     * Dongfang Jinxin SeaboxSQL supports the PostgreSQL syntax and driver ecosystem; its
     * documentation gives 5432 as the default service port. SeaBoxMPP is an MPP product
     * built from several SeaboxSQL instances and is reached through the same protocol.
     */
    SEABOXSQL("db.type.SEABOXSQL", "org.postgresql.Driver",
            "jdbc:postgresql://%s:%d/%s",
            5432, DialectFamily.POSTGRES),

    HIGHGO("db.type.HIGHGO", "com.highgo.jdbc.Driver",
            "jdbc:highgo://%s:%d/%s",
            5866, DialectFamily.POSTGRES),

    VASTBASE("db.type.VASTBASE", "cn.com.vastbase.Driver",
            "jdbc:vastbase://%s:%d/%s",
            5432, DialectFamily.POSTGRES),

    /**
     * Oracle-compatible: unquoted identifiers fold to upper case and MERGE upserts apply,
     * so it joins the Oracle dialect family. The URL prefix is {@code jdbc:yasdb:},
     * not {@code jdbc:yashandb:} — verified against the driver itself.
     */
    YASHANDB("db.type.YASHANDB", "com.yashandb.jdbc.Driver",
            "jdbc:yasdb://%s:%d/%s",
            1688, DialectFamily.ORACLE),

    GBASE("db.type.GBASE", "com.gbase.jdbc.Driver",
            "jdbc:gbase://%s:%d/%s",
            5258, DialectFamily.MYSQL),

    OSCAR("db.type.OSCAR", "com.oscar.Driver",
            "jdbc:oscar://%s:%d/%s",
            2003, DialectFamily.POSTGRES),

    /**
     * GENERIC, not MYSQL: H2 only understands MySQL syntax when explicitly started with
     * {@code MODE=MySQL}, and the MySQL dialect's DDL suffix
     * ({@code ENGINE=InnoDB DEFAULT CHARSET=utf8mb4}) is rejected by a default-mode H2.
     * The generic dialect emits standard SQL that H2 accepts in any mode.
     */
    H2("db.type.H2", "org.h2.Driver",
            "jdbc:h2:tcp://%s:%d/%s",
            9092, DialectFamily.GENERIC),

    CUSTOM("db.type.CUSTOM", null, null, 0, DialectFamily.GENERIC);

    /**
     * Products that ship their own JDBC driver which is <em>not</em> redistributed here, while
     * a bundled substitute still connects them: the vendor driver is optional, so the form
     * offers it behind a switch instead of demanding a jar.
     *
     * <p>Deliberately narrow. A preset whose driver is bundled (达梦, Kingbase, openGauss,
     * OceanBase, HighGo, Vastbase, YashanDB, ...) has nothing to fill in and gets no card,
     * and neither has a product whose official access already is the bundled driver
     * (TiDB, TDSQL, PolarDB, UPDRDB, KunDB, RASESQL, SeaboxSQL). Presets that cannot connect
     * at all without a jar (GaussDB, GBase, Oscar, DB2) are detected from the classpath and
     * get a required card instead.
     */
    private static final Set<DatabaseType> OPTIONAL_VENDOR_DRIVER_PRESETS =
            Collections.unmodifiableSet(EnumSet.of(GOLDENDB, GREATDB));

    /** Whether the connection form offers an <em>optional</em> vendor URL / jar / class override. */
    public boolean supportsVendorDriver() {
        return OPTIONAL_VENDOR_DRIVER_PRESETS.contains(this);
    }

    /**
     * i18n key for the human label, not the label itself.
     *
     * <p>These used to be hardcoded bilingual literals — "达梦 DM", "自定义 Custom" — on the
     * theory that showing both languages suits either reader. It does not: an English user saw
     * Chinese text on every connection form, and because a literal needs no lookup it resolved
     * cleanly and never tripped the bundle guard. The domestic products now carry their own
     * English brand in {@code messages.properties} (DAMENG, KingBase, HighGo…) and their Chinese
     * name in {@code messages_zh_CN.properties}, which is what each reader expects.
     */
    private final String messageKey;

    private final String driverClassName;
    private final String urlTemplate;
    /** Template without a port, for products where a blank port is meaningful. */
    private final String portlessUrlTemplate;
    private final int defaultPort;
    private final DialectFamily family;

    DatabaseType(String messageKey, String driverClassName, String urlTemplate,
                 int defaultPort, DialectFamily family) {
        this(messageKey, driverClassName, urlTemplate, null, defaultPort, family);
    }

    DatabaseType(String messageKey, String driverClassName, String urlTemplate,
                 String portlessUrlTemplate, int defaultPort, DialectFamily family) {
        this.messageKey = messageKey;
        this.driverClassName = driverClassName;
        this.urlTemplate = urlTemplate;
        this.portlessUrlTemplate = portlessUrlTemplate;
        this.defaultPort = defaultPort;
        this.family = family;
    }

    public boolean isCustom() {
        return this == CUSTOM;
    }

    /**
     * Whether leaving the port blank is a supported choice that must be preserved.
     *
     * <p>Only products with a {@link #portlessUrlTemplate} qualify. For everything else a
     * blank port still means "use the preset default", so the form and the save path keep
     * substituting it.
     */
    public boolean allowsBlankPort() {
        return portlessUrlTemplate != null;
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
        // Every preset that reuses Connector/J with the same URL flags qualifies: TiDB,
        // GoldenDB, TDSQL, PolarDB and the three domestic MySQL-compatible presets all
        // carry useAffectedRows=true, so ON DUPLICATE KEY UPDATE reports 1/2/0 exactly like
        // MySQL. MariaDB keeps its own driver and no such flag, so it stays unclassified.
        return urlTemplate != null && urlTemplate.contains("useAffectedRows=true");
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

    /**
     * A preset that speaks the given family, standing in for it wherever a family-aware
     * decision is made from a {@link DatabaseType} (type mapping, DDL, schema folding,
     * identity reseed). Null for {@code GENERIC}, {@code CUSTOM} and null: none of them has
     * a preset to represent it, so the generic behavior stays in charge.
     */
    public static DatabaseType representativeOf(DialectFamily family) {
        if (family == null) {
            return null;
        }
        switch (family) {
            case ORACLE:
                return ORACLE;
            case MYSQL:
                return MYSQL;
            case POSTGRES:
                return POSTGRESQL;
            case SQLSERVER:
                return SQLSERVER;
            case DB2:
                return DB2;
            default:
                return null;
        }
    }

    /** SQL dialect groupings. Several products map onto one family. */
    public enum DialectFamily {
        MYSQL, ORACLE, POSTGRES, SQLSERVER, DB2, GENERIC
    }
}
