package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * Each product family stores view text and routine bodies in its own catalogs; a type that
 * silently lands on the JDBC-only generic reader keeps tables and indexes but loses view
 * definitions (INFORMATION_SCHEMA only) and all routine bodies. Every type with a family
 * reader must be routed to it.
 */
class MetadataReaderFactoryTest {

    private final MetadataReaderFactory factory = new MetadataReaderFactory(List.of(
            new MySQLMetadataReader(),
            new PostgresMetadataReader(),
            new OracleMetadataReader(),
            new SqlServerMetadataReader(),
            new Db2MetadataReader(),
            new GenericMetadataReader()));

    @Test
    @DisplayName("MySQL 协议系（含 OceanBase、TiDB、GBase）走 MySQL 读取器")
    void mysqlProtocolTypesGetTheMysqlReader() {
        for (DatabaseType type : List.of(DatabaseType.MYSQL, DatabaseType.MARIADB,
                DatabaseType.GBASE, DatabaseType.OCEANBASE, DatabaseType.TIDB)) {
            assertThat(factory.forType(type))
                    .as("%s 应路由到 MySQLMetadataReader", type)
                    .isInstanceOf(MySQLMetadataReader.class);
        }
    }

    @Test
    @DisplayName("PostgreSQL 系（含瀚高、海量）走 PostgreSQL 读取器")
    void postgresDerivedTypesGetThePostgresReader() {
        for (DatabaseType type : List.of(DatabaseType.POSTGRESQL, DatabaseType.OPENGAUSS,
                DatabaseType.KINGBASE, DatabaseType.OSCAR, DatabaseType.HIGHGO,
                DatabaseType.VASTBASE)) {
            assertThat(factory.forType(type))
                    .as("%s 应路由到 PostgresMetadataReader", type)
                    .isInstanceOf(PostgresMetadataReader.class);
        }
    }

    @Test
    @DisplayName("Oracle 字典系（达梦、崖山）走 Oracle 读取器")
    void oracleDictionaryTypesGetTheOracleReader() {
        for (DatabaseType type : List.of(DatabaseType.ORACLE, DatabaseType.DM,
                DatabaseType.YASHANDB)) {
            assertThat(factory.forType(type))
                    .as("%s 应路由到 OracleMetadataReader", type)
                    .isInstanceOf(OracleMetadataReader.class);
        }
    }

    @Test
    @DisplayName("SQL Server 与 DB2 各走专属读取器")
    void sqlServerAndDb2GetTheirOwnReaders() {
        assertThat(factory.forType(DatabaseType.SQLSERVER))
                .isInstanceOf(SqlServerMetadataReader.class);
        assertThat(factory.forType(DatabaseType.DB2))
                .isInstanceOf(Db2MetadataReader.class);
    }

    @Test
    @DisplayName("自定义类型与 null 回落到通用读取器，不抛异常")
    void customAndNullFallBackToTheGenericReader() {
        assertThat(factory.forType(DatabaseType.CUSTOM))
                .isExactlyInstanceOf(GenericMetadataReader.class);
        assertThat(factory.forType(null))
                .isExactlyInstanceOf(GenericMetadataReader.class);
    }
}
