package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P2: two loader-leak guards —
 *
 * <ul>
 *   <li>a driver that self-registers from its static block must leave only the shim registered,
 *       not a raw registration pinning the child loader;</li>
 *   <li>a failed load (class missing from the jar, registration failure) must not strand the
 *       freshly built loader in the cache with its jar handles open.</li>
 * </ul>
 */
class DriverLoaderCleanupTest {

    @TempDir
    File temp;

    @Test
    void aSelfRegisteringDriverKeepsOnlyTheShimRegistered() throws Exception {
        File jar = jarWithSelfRegisteringDriver();
        DriverLoader loader = new DriverLoader();

        loader.ensureDriverLoaded("probe.DynSelfRegDriver", jar.getAbsolutePath());

        Object delegate = findDelegate(loader, "probe.DynSelfRegDriver");
        Method probe = delegate.getClass().getMethod("rawStillRegistered");
        // Called on a class loaded by the child loader, getDrivers() would still see the raw
        // self-registration the static block made. The fix deregisters it.
        assertThat(probe.invoke(delegate)).isEqualTo(false);

        loader.shutdown();
    }

    @Test
    void aFailedLoadClosesAndEvictsTheFreshLoader() throws Exception {
        File jar = emptyJar("useless.jar");
        DriverLoader loader = new DriverLoader();

        assertThatThrownBy(() ->
                loader.ensureDriverLoaded("probe.DoesNotExist", jar.getAbsolutePath()))
                .isInstanceOf(IllegalStateException.class);

        Map<String, ?> cache = readPrivateMap(loader, "loaderCache");
        URL jarUrl = jar.toURI().toURL();
        for (Object child : cache.values()) {
            URL[] urls = (URL[]) child.getClass().getMethod("getURLs").invoke(child);
            for (URL url : urls) {
                assertThat(url).as("loader for the failed jar must be evicted").isNotEqualTo(jarUrl);
            }
        }

        // A corrected attempt must be able to start fresh and succeed.
        File goodJar = jarWithSelfRegisteringDriver();
        loader.ensureDriverLoaded("probe.DynSelfRegDriver", goodJar.getAbsolutePath());
        loader.shutdown();
    }

    @Test
    void aDriverClassThatPrefixesAnotherKeepsItsOwnRegistration() throws Exception {
        // Registration keys are class name + separator + jar path, and reloading one class
        // deregisters its stale shims by prefix. With an empty separator that prefix also
        // matched every longer class name, so loading probe.Driver silently dropped
        // probe.DriverX's shim and its connections lost their driver. The separator is a
        // literal U+0001 for exactly this reason; this test pins the behaviour, not the byte.
        File jar = jarWithPrefixDrivers();
        DriverLoader loader = new DriverLoader();

        loader.ensureDriverLoaded("probe.DriverX", jar.getAbsolutePath());
        loader.ensureDriverLoaded("probe.Driver", jar.getAbsolutePath());

        assertThat(findDelegate(loader, "probe.Driver")).isNotNull();
        assertThat(findDelegate(loader, "probe.DriverX")).isNotNull();
        loader.shutdown();
    }

    // --- fixtures ----------------------------------------------------------------------

    private Object findDelegate(DriverLoader loader, String driverClassName) throws Exception {
        Map<String, ?> registered = readPrivateMap(loader, "registered");
        for (Object shim : registered.values()) {
            Object delegate = shim.getClass().getMethod("getDelegate").invoke(shim);
            if (delegate.getClass().getName().equals(driverClassName)) {
                return delegate;
            }
        }
        throw new AssertionError("shim for " + driverClassName + " not found");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPrivateMap(DriverLoader loader, String fieldName)
            throws Exception {
        Field field = DriverLoader.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, Object>) field.get(loader);
    }

    private File emptyJar(String name) throws IOException {
        File jar = new File(temp, name);
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.flush();
        }
        return jar;
    }

    /**
     * Two drivers whose class names prefix each other, compiled at runtime and jarred: only
     * the child loader can supply them, so registration bookkeeping is the only thing under
     * test.
     */
    private File jarWithPrefixDrivers() throws IOException {
        // Fully qualified names throughout: a class named Driver cannot resolve the short
        // name Driver to java.sql.Driver — it would find itself.
        String template = "package probe;\n"
                + "public class %s implements java.sql.Driver {\n"
                + "  public java.sql.Connection connect(String url, java.util.Properties info)"
                + " { return null; }\n"
                + "  public boolean acceptsURL(String url) { return false; }\n"
                + "  public java.sql.DriverPropertyInfo[] getPropertyInfo(String url,"
                + " java.util.Properties info) { return new java.sql.DriverPropertyInfo[0]; }\n"
                + "  public int getMajorVersion() { return 0; }\n"
                + "  public int getMinorVersion() { return 0; }\n"
                + "  public boolean jdbcCompliant() { return false; }\n"
                + "  public java.util.logging.Logger getParentLogger() { return null; }\n"
                + "}\n";
        for (String name : List.of("Driver", "DriverX")) {
            java.nio.file.Files.writeString(
                    new File(temp, name + ".java").toPath(), String.format(template, name));
        }

        String[] args = {"-d", temp.getAbsolutePath(),
                new File(temp, "Driver.java").getAbsolutePath(),
                new File(temp, "DriverX.java").getAbsolutePath()};
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler.run(null, null, null, args))
                .as("runtime compilation failed").isEqualTo(0);

        File jar = new File(temp, "prefix-drivers.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            for (String name : List.of("Driver", "DriverX")) {
                File compiled = new File(temp, "probe/" + name + ".class");
                assertThat(compiled).exists();
                JarEntry entry = new JarEntry("probe/" + name + ".class");
                out.putNextEntry(entry);
                out.write(java.nio.file.Files.readAllBytes(compiled.toPath()));
                out.closeEntry();
            }
        }
        return jar;
    }

    /** Compiles a self-registering driver at runtime and jars it, so it exists nowhere on the
     *  test classpath and can only come from the child loader. */
    private File jarWithSelfRegisteringDriver() throws IOException {
        File source = new File(temp, "DynSelfRegDriver.java");
        String src = "package probe;\n"
                + "import java.sql.*;\n"
                + "import java.util.Properties;\n"
                + "import java.util.logging.Logger;\n"
                + "public class DynSelfRegDriver implements Driver {\n"
                + "  static {\n"
                + "    try { DriverManager.registerDriver(new DynSelfRegDriver()); }\n"
                + "    catch (SQLException e) { throw new RuntimeException(e); }\n"
                + "  }\n"
                + "  public boolean rawStillRegistered() {\n"
                + "    java.util.Enumeration<Driver> all = DriverManager.getDrivers();\n"
                + "    while (all.hasMoreElements()) { if (all.nextElement() == this) return true; }\n"
                + "    return false;\n"
                + "  }\n"
                + "  public Connection connect(String url, Properties info) { return null; }\n"
                + "  public boolean acceptsURL(String url) { return false; }\n"
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

        File classes = new File(temp, "probe/DynSelfRegDriver.class");
        assertThat(classes).exists();
        File jar = new File(temp, "driver.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            JarEntry entry = new JarEntry("probe/DynSelfRegDriver.class");
            out.putNextEntry(entry);
            out.write(java.nio.file.Files.readAllBytes(classes.toPath()));
            out.closeEntry();
        }
        return jar;
    }
}
