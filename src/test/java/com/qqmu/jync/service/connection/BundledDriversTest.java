package com.qqmu.jync.service.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * Packaging guard for the JDBC drivers bundled in the distribution.
 *
 * <p>Without this, removing (or forgetting) a driver dependency in {@code pom.xml} compiles
 * fine and only fails at the customer site when they click "Test connection". Instead every
 * preset type must either resolve from the application classpath or be explicitly classified
 * as external (user-supplied jar). The external set is currently GBase and Oscar — neither
 * vendor publishes an artifact on Maven Central — plus CUSTOM, which never has a driver.
 */
class BundledDriversTest {

    private static final Set<DatabaseType> EXTERNAL =
            EnumSet.of(DatabaseType.CUSTOM, DatabaseType.GBASE, DatabaseType.OSCAR);

    @Test
    @DisplayName("每个预设类型要么内置驱动,要么显式走外部 jar")
    void everyTypeIsEitherBundledOrExplicitlyExternal() {
        for (DatabaseType type : DatabaseType.values()) {
            boolean present = DriverPresence.isPresent(type.getDriverClassName());
            if (EXTERNAL.contains(type)) {
                assertFalse(present,
                        type + " 未获再分发授权,不应出现在 classpath 上,必须由用户提供 jar");
            } else {
                assertTrue(present,
                        type + " 的驱动类不在 classpath 上,检查 pom.xml 是否漏掉驱动依赖");
            }
        }
    }

    @Test
    @DisplayName("内置驱动预设共 15 个:MySQL/MariaDB/Oracle/SQL Server/DB2/Postgres/openGauss/达梦/金仓/OceanBase/TiDB/瀚高/海量/崖山/H2")
    void fifteenDriversAreBundled() {
        long bundled = Arrays.stream(DatabaseType.values())
                .filter(t -> !EXTERNAL.contains(t))
                .filter(t -> DriverPresence.isPresent(t.getDriverClassName()))
                .count();
        assertEquals(15, bundled);
    }

    /**
     * A URL template that the type's own driver rejects fails only at the customer site,
     * when they click "Test connection" — the same blind spot the presence check above
     * closes for missing jars. TiDB borrows the MySQL driver, which is exactly the point
     * being tested: the borrowed template must be accepted too.
     */
    @Test
    @DisplayName("每个内置类型的 URL 模板都能被其驱动接受")
    void bundledDriversAcceptTheirUrlTemplate() throws Exception {
        for (DatabaseType type : DatabaseType.values()) {
            if (EXTERNAL.contains(type) || type.getUrlTemplate() == null) {
                continue;
            }
            String url = String.format(type.getUrlTemplate(), "localhost", type.getDefaultPort(), "db");
            java.sql.Driver driver = (java.sql.Driver) Class.forName(type.getDriverClassName())
                    .getDeclaredConstructor().newInstance();
            assertTrue(driver.acceptsURL(url), type + " 的驱动不接受自己的 URL 模板: " + url);
        }
    }

    @Test
    @DisplayName("DriverPresence 对空白类名和不存在的类返回 false")
    void presenceHandlesBlankAndMissing() {
        assertFalse(DriverPresence.isPresent(null));
        assertFalse(DriverPresence.isPresent("   "));
        assertFalse(DriverPresence.isPresent("com.example.DoesNotExistDriver"));
        assertTrue(DriverPresence.isPresent("org.h2.Driver"));
    }

    @Test
    @DisplayName("externalJarRequired:GBase/神通需要外部 jar,内置类型与 CUSTOM 不需要")
    void externalJarRequirementByType() {
        assertTrue(DriverPresence.externalJarRequired(DatabaseType.GBASE, null));
        assertTrue(DriverPresence.externalJarRequired(DatabaseType.OSCAR, "  "));
        assertFalse(DriverPresence.externalJarRequired(DatabaseType.DM, null));
        assertFalse(DriverPresence.externalJarRequired(DatabaseType.MYSQL, null));
        assertFalse(DriverPresence.externalJarRequired(DatabaseType.CUSTOM, "com.example.X"));
        assertFalse(DriverPresence.externalJarRequired(null, null));
    }

    @Test
    @DisplayName("自定义驱动类若恰好在 classpath 上,则不强制要求 jar")
    void overriddenDriverOnClasspathNeedsNoJar() {
        assertFalse(DriverPresence.externalJarRequired(DatabaseType.GBASE, "org.h2.Driver"));
        // 覆盖成一个同样不在 classpath 的类,仍然需要 jar
        assertTrue(DriverPresence.externalJarRequired(DatabaseType.GBASE, "com.example.X"));
    }
}
