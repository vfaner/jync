package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.DatabaseType.DialectFamily;

/**
 * Guards the compatibility presets added for domestic commercial databases.
 *
 * <p>These products are not new dialect families: each one is documented by its vendor as
 * speaking the MySQL or PostgreSQL protocol and SQL dialect, so the preset only supplies a
 * label, a URL template and a default port. The dialect, metadata reader, type mapping and
 * upsert form all come from the existing family — the same code path already covered by the
 * MySQL/PostgreSQL tests. Pin that contract here so a later edit cannot quietly move one of
 * them to {@code GENERIC}, which would silently downgrade structure sync and data writes.
 */
class CompatiblePresetsTest {

    @Test
    @DisplayName("MySQL 协议兼容预设复用内置 Connector/J 与 MySQL 方言")
    void mysqlCompatiblePresetsReuseTheBundledDriver() {
        for (DatabaseType type : new DatabaseType[] {
                DatabaseType.GREATDB, DatabaseType.UPDRDB, DatabaseType.KUNDB }) {
            assertThat(type.getFamily()).as(type.name()).isEqualTo(DialectFamily.MYSQL);
            assertThat(type.getDriverClassName()).as(type.name())
                    .isEqualTo("com.mysql.cj.jdbc.Driver");
            assertThat(type.getUrlTemplate()).as(type.name()).startsWith("jdbc:mysql://");
            assertThat(DriverPresence.isPresent(type.getDriverClassName())).as(type.name())
                    .isTrue();
        }
        assertThat(DatabaseType.GREATDB.getDefaultPort()).isEqualTo(3306);
        assertThat(DatabaseType.UPDRDB.getDefaultPort()).isEqualTo(3306);
        assertThat(DatabaseType.KUNDB.getDefaultPort()).isEqualTo(9669);
    }

    @Test
    @DisplayName("PostgreSQL 协议兼容预设复用内置 PG 驱动与 PostgreSQL 方言")
    void postgresCompatiblePresetsReuseTheBundledDriver() {
        for (DatabaseType type : new DatabaseType[] {
                DatabaseType.RASESQL, DatabaseType.SEABOXSQL }) {
            assertThat(type.getFamily()).as(type.name()).isEqualTo(DialectFamily.POSTGRES);
            assertThat(type.getDriverClassName()).as(type.name())
                    .isEqualTo("org.postgresql.Driver");
            assertThat(type.getUrlTemplate()).as(type.name()).startsWith("jdbc:postgresql://");
            assertThat(DriverPresence.isPresent(type.getDriverClassName())).as(type.name())
                    .isTrue();
        }
        assertThat(DatabaseType.RASESQL.getDefaultPort()).isEqualTo(5432);
        assertThat(DatabaseType.SEABOXSQL.getDefaultPort()).isEqualTo(5432);
    }

    @Test
    @DisplayName("MySQL 兼容预设的 URL 同样启用 affected-rows 语义，新增/更新可分类")
    void mysqlCompatiblePresetsClassifyInsertFromUpdate() {
        for (DatabaseType type : new DatabaseType[] {
                DatabaseType.GREATDB, DatabaseType.UPDRDB, DatabaseType.KUNDB }) {
            assertThat(type.getUrlTemplate()).as(type.name()).contains("useAffectedRows=true");
            assertThat(type.upsertCountsDistinguishInsertFromUpdate()).as(type.name()).isTrue();
        }
        // PostgreSQL 系没有 affected-rows 语义，保持不分类是正确行为。
        assertThat(DatabaseType.RASESQL.upsertCountsDistinguishInsertFromUpdate()).isFalse();
        assertThat(DatabaseType.SEABOXSQL.upsertCountsDistinguishInsertFromUpdate()).isFalse();
    }
}
