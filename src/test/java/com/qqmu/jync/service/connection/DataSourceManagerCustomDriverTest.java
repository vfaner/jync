package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.util.CryptoUtil;
import com.zaxxer.hikari.HikariDataSource;

/**
 * A driver that exists only in a user-supplied jar must be usable by HikariCP.
 *
 * <p>HikariCP resolves {@code driverClassName} through the thread context class loader and
 * then its own, so a class loaded into a child {@code URLClassLoader} is invisible to it.
 * "Test connection" then fails with "Failed to load driver class ... in either of
 * HikariConfig class loader or Thread context classloader" even though the jar path is
 * correct and "detect driver class" found the class — exactly the GaussDB report.
 */
class DataSourceManagerCustomDriverTest {

    @TempDir
    File temp;

    @Test
    void aDriverFromACustomJarCanBuildAHikariPool() throws Exception {
        File jar = jarWithDriver();
        SyncProperties properties = new SyncProperties();
        DataSourceManager manager = new DataSourceManager(
                new DriverLoader(), new CryptoUtil(properties), properties);
        DatabaseConfig config = customConfig(jar);

        ClassLoader callerLoader = Thread.currentThread().getContextClassLoader();

        assertThatCode(() -> {
            try (HikariDataSource dataSource = manager.createTransientDataSource(config)) {
                assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:dyn://localhost/test");
            }
        }).as("a driver only present in the configured jar must not be invisible to Hikari")
                .doesNotThrowAnyException();

        // The swap is scoped to pool creation: leaving the child loader on the caller's
        // thread would leak it into unrelated class loading.
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(callerLoader);
    }

    /**
     * The connection-test endpoint returns a message key to the browser, which resolves it
     * through {@code JyncMessages}. A key without the {@code error.} prefix matches no bundle
     * entry, so the user sees the raw key instead of "a driver class name is required".
     */
    @Test
    void missingCustomDriverReportsATranslatableKey() {
        DataSourceManager manager = manager();
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.CUSTOM);
        config.setCustomUrl("jdbc:vendor://localhost/db");

        assertThatThrownBy(() -> manager.resolveDriverClass(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.custom.driver.required");
    }

    @Test
    void missingCustomUrlReportsATranslatableKey() {
        DataSourceManager manager = manager();
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.CUSTOM);

        assertThatThrownBy(() -> manager.buildJdbcUrl(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.custom.url.required");
    }

    /** A hand-posted form can arrive without a type; that must be a message, not an NPE. */
    @Test
    void missingTypeReportsAChoiceInsteadOfNpe() {
        DataSourceManager manager = manager();
        DatabaseConfig config = new DatabaseConfig();
        config.setName("no-type");
        // A form post with an empty type binds null over the field's MYSQL default.
        config.setType(null);

        assertThatThrownBy(() -> manager.resolveDriverClass(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.connection.type.required");
        assertThatThrownBy(() -> manager.buildJdbcUrl(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.connection.type.required");
    }

    private DataSourceManager manager() {
        SyncProperties properties = new SyncProperties();
        return new DataSourceManager(new DriverLoader(), new CryptoUtil(properties), properties);
    }

    private DatabaseConfig customConfig(File jar) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName("gauss");
        config.setType(DatabaseType.CUSTOM);
        config.setCustomDriver("probe.DynDriver");
        config.setCustomJarPath(jar.getAbsolutePath());
        config.setCustomUrl("jdbc:dyn://localhost/test");
        config.setUsername("user");
        config.setPassword("secret");
        return config;
    }

    /** Compiles a minimal driver at runtime and jars it, so it exists nowhere on the test
     *  classpath and can only come from the configured jar. */
    private File jarWithDriver() throws IOException {
        File source = new File(temp, "DynDriver.java");
        String src = "package probe;\n"
                + "import java.lang.reflect.Proxy;\n"
                + "import java.sql.*;\n"
                + "import java.util.Properties;\n"
                + "import java.util.logging.Logger;\n"
                + "public class DynDriver implements Driver {\n"
                + "  public Connection connect(String url, Properties info) {\n"
                + "    if (!acceptsURL(url)) return null;\n"
                + "    return (Connection) Proxy.newProxyInstance(DynDriver.class.getClassLoader(),\n"
                + "        new Class<?>[]{Connection.class}, (p, m, a) -> {\n"
                + "          if (m.getName().equals(\"isValid\")) return true;\n"
                + "          if (m.getName().equals(\"getAutoCommit\")) return true;\n"
                + "          if (m.getName().equals(\"isClosed\")) return false;\n"
                + "          Class<?> r = m.getReturnType();\n"
                + "          if (r == boolean.class) return false;\n"
                + "          if (r == int.class) return 0;\n"
                + "          if (r == long.class) return 0L;\n"
                + "          return null;\n"
                + "        });\n"
                + "  }\n"
                + "  public boolean acceptsURL(String url) {\n"
                + "    return url != null && url.startsWith(\"jdbc:dyn:\"); }\n"
                + "  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {\n"
                + "    return new DriverPropertyInfo[0]; }\n"
                + "  public int getMajorVersion() { return 0; }\n"
                + "  public int getMinorVersion() { return 0; }\n"
                + "  public boolean jdbcCompliant() { return false; }\n"
                + "  public Logger getParentLogger() { return null; }\n"
                + "}\n";
        java.nio.file.Files.writeString(source.toPath(), src);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, "-d", temp.getAbsolutePath(),
                source.getAbsolutePath());
        assertThat(rc).as("runtime compilation failed").isEqualTo(0);

        File classes = new File(temp, "probe/DynDriver.class");
        assertThat(classes).exists();
        File jar = new File(temp, "gauss.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry("probe/DynDriver.class"));
            out.write(java.nio.file.Files.readAllBytes(classes.toPath()));
            out.closeEntry();
        }
        return jar;
    }
}
