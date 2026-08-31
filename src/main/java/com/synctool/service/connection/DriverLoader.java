package com.synctool.service.connection;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Loads JDBC drivers from user-supplied jars at runtime and registers them so
 * {@link DriverManager} can hand out connections for their URLs.
 *
 * <p>Class loaders are cached per jar path so repeated connections to the same custom
 * database do not leak a loader per attempt, and so a driver's static state is shared.
 */
@Component
@Slf4j
public class DriverLoader {

    /** Cache key is the canonical jar path (or the joined paths of a directory). */
    private final Map<String, URLClassLoader> loaderCache = new ConcurrentHashMap<>();

    /** Driver class name -> shim already registered with DriverManager. */
    private final Map<String, DriverShim> registered = new ConcurrentHashMap<>();

    /**
     * Ensures {@code driverClassName} is loadable and registered.
     *
     * @param driverClassName fully qualified {@link Driver} implementation
     * @param jarPath         path to a jar file or a directory of jars; may be blank when
     *                        the driver is already on the application classpath
     * @return the class loader that owns the driver, for callers that must set the
     *         thread context loader before opening a connection
     */
    public ClassLoader ensureDriverLoaded(String driverClassName, String jarPath) {
        if (!StringUtils.hasText(driverClassName)) {
            throw new IllegalArgumentException("driver.class.required");
        }

        // Already registered from a previous call.
        DriverShim existing = registered.get(driverClassName);
        if (existing != null) {
            return existing.getDelegate().getClass().getClassLoader();
        }

        if (!StringUtils.hasText(jarPath)) {
            // Expect the driver to be bundled with the application.
            try {
                Class.forName(driverClassName);
                log.debug("Driver {} found on the application classpath", driverClassName);
                return getClass().getClassLoader();
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Driver " + driverClassName + " is not on the classpath and no jar path was "
                                + "supplied. Provide the driver jar path in the connection settings.", e);
            }
        }

        URLClassLoader loader = loaderCache.computeIfAbsent(canonical(jarPath), key -> buildLoader(jarPath));

        try {
            Class<?> driverClass = Class.forName(driverClassName, true, loader);
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            DriverShim shim = new DriverShim(driver);
            DriverManager.registerDriver(shim);
            registered.put(driverClassName, shim);
            log.info("Registered dynamically loaded driver {} from {}", driverClassName, jarPath);
            return loader;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Driver class " + driverClassName
                    + " was not found inside " + jarPath, e);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to register driver " + driverClassName, e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate driver " + driverClassName
                    + "; it may require a different loading strategy", e);
        }
    }

    private URLClassLoader buildLoader(String jarPath) {
        List<URL> urls = new ArrayList<>();
        for (String part : jarPath.split(File.pathSeparator.equals(":") ? "[:;,]" : "[;,]")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            File file = new File(trimmed);
            if (!file.exists()) {
                throw new IllegalArgumentException("Driver jar path does not exist: " + trimmed);
            }
            if (file.isDirectory()) {
                File[] jars = file.listFiles(f -> f.getName().toLowerCase().endsWith(".jar"));
                if (jars == null || jars.length == 0) {
                    throw new IllegalArgumentException("No jar files found in directory: " + trimmed);
                }
                for (File jar : jars) {
                    urls.add(toUrl(jar));
                }
            } else {
                urls.add(toUrl(file));
            }
        }
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("No usable driver jars in: " + jarPath);
        }
        // Parent is this application's loader so the driver can see java.sql.*.
        return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
    }

    private URL toUrl(File file) {
        try {
            return file.toURI().toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid driver jar path: " + file, e);
        }
    }

    private String canonical(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (Exception e) {
            return path;
        }
    }

    /** Verifies a jar contains the named driver, without keeping it registered. */
    public boolean validateDriverJar(String driverClassName, String jarPath) {
        try {
            ensureDriverLoaded(driverClassName, jarPath);
            return true;
        } catch (Exception e) {
            log.warn("Driver validation failed for {} in {}: {}", driverClassName, jarPath, e.getMessage());
            return false;
        }
    }

    /** Lists candidate driver class names found in a jar, to help the user fill the form. */
    public List<String> discoverDriverClasses(String jarPath) {
        List<String> found = new ArrayList<>();
        for (String part : jarPath.split("[;,]")) {
            File file = new File(part.trim());
            if (!file.isFile()) {
                continue;
            }
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
                // A compliant driver advertises itself through the service loader file.
                java.util.jar.JarEntry svc = jar.getJarEntry("META-INF/services/java.sql.Driver");
                if (svc != null) {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(jar.getInputStream(svc), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String cls = line.trim();
                            if (!cls.isEmpty() && !cls.startsWith("#")) {
                                found.add(cls);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not scan {} for drivers: {}", part, e.getMessage());
            }
        }
        return found;
    }
}
