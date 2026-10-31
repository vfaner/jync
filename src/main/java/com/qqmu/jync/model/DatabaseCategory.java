package com.qqmu.jync.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import lombok.Getter;

/**
 * How the connection form groups the database presets.
 *
 * <p>Thirty-odd presets in one flat list are hard to scan, so the form renders them under these
 * headings and the browser-side picker filters across them. The grouping is also a guard: every
 * preset must belong to exactly one category, which a test pins, so adding a preset without
 * deciding where it belongs fails the build instead of silently hiding the type from the form.
 *
 * <p>Category order is the order of the constants below — mainstream products first, special
 * cases last. Within a category the presets keep their {@link DatabaseType} declaration order.
 */
@Getter
public enum DatabaseCategory {

    BASIC("db.category.basic",
            DatabaseType.MYSQL, DatabaseType.MARIADB, DatabaseType.POSTGRESQL,
            DatabaseType.ORACLE, DatabaseType.SQLSERVER, DatabaseType.DB2),

    /**
     * Domestic products, including the MySQL- and PostgreSQL-compatible distributed databases:
     * an operator looking for 达梦 or 崖山 scans this group, not the protocol family.
     */
    DOMESTIC("db.category.domestic",
            DatabaseType.OPENGAUSS, DatabaseType.GAUSSDB, DatabaseType.DM,
            DatabaseType.KINGBASE, DatabaseType.OCEANBASE, DatabaseType.TIDB,
            DatabaseType.GOLDENDB, DatabaseType.TDSQL, DatabaseType.POLARDB,
            DatabaseType.GREATDB, DatabaseType.UPDRDB, DatabaseType.KUNDB,
            DatabaseType.RASESQL, DatabaseType.SEABOXSQL, DatabaseType.HIGHGO,
            DatabaseType.VASTBASE, DatabaseType.YASHANDB, DatabaseType.GBASE,
            DatabaseType.OSCAR),

    ANALYTICS("db.category.analytics",
            DatabaseType.HANA, DatabaseType.DORIS, DatabaseType.CLICKHOUSE,
            DatabaseType.HIVE, DatabaseType.IMPALA),

    NOSQL("db.category.nosql",
            DatabaseType.ELASTICSEARCH, DatabaseType.MONGODB, DatabaseType.MONGODB_NATIVE),

    /** Embedded/test products and the bring-your-own-driver escape hatch. */
    OTHER("db.category.other",
            DatabaseType.H2, DatabaseType.CUSTOM);

    private final String messageKey;
    private final List<DatabaseType> types;

    DatabaseCategory(String messageKey, DatabaseType first, DatabaseType... rest) {
        this.messageKey = messageKey;
        Set<DatabaseType> members = EnumSet.of(first, rest);
        this.types = Collections.unmodifiableList(new java.util.ArrayList<>(members));
    }

    /** The category a preset belongs to; every preset has exactly one. */
    public static DatabaseCategory of(DatabaseType type) {
        for (DatabaseCategory category : values()) {
            if (category.types.contains(type)) {
                return category;
            }
        }
        throw new IllegalArgumentException("No database category for " + type);
    }
}
