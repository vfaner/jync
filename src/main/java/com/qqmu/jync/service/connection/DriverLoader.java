package com.qqmu.jync.service.connection;

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

    /** Accepts the platform path separator ({@code :} on Unix, {@code ;} on Windows) plus
     *  {@code ,}, which the form hints at. Detection and loading must agree on this. */
    private static final String PATH_SPLIT =
            File.pathSeparator.equals(":") ? "[:;,]" : "[;,]";

    /** Cache key is the canonical jar path (or the joined paths of a directory). */
    private final Map<String, URLClassLoader> loaderCache = new ConcurrentHashMap<>();

    /**
     * Driver class name + a U+0001 separator + canonical jar path -> shim registered with
     * DriverManager. The jar path is part of the key so editing a connection's jar path
     * actually loads the new jar; a class-name-only key would keep serving the old one.
     */
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

        // Already registered from a previous call with the same jar.
        DriverShim existing = registered.get(registeredKey(driverClassName, jarPath));
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
            // Retire a shim for the same class that came from a different jar path (the user
            // edited the connection). It must go BEFORE the new registration: DriverManager
            // hands a URL to the first driver that accepts it, so a leftover old shim would
            // keep routing connections through the old jar.
            deregisterStaleShims(driverClassName);
            DriverManager.registerDriver(shim);
            registered.put(registeredKey(driverClassName, jarPath), shim);
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
        for (String part : jarPath.split(PATH_SPLIT)) {
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

    /** Key for {@link #registered}: class name plus the canonical jar path it came from. */
    private String registeredKey(String driverClassName, String jarPath) {
        String suffix = StringUtils.hasText(jarPath) ? canonical(jarPath) : "";
        return driverClassName + "\\u0001" + suffix;
    }

    /** Removes and deregisters every shim previously registered for this driver class. */
    private void deregisterStaleShims(String driverClassName) {
        String prefix = driverClassName + "\\u0001";
        for (String key : registered.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            DriverShim stale = registered.remove(key);
            if (stale == null) {
                continue;
            }
            try {
                DriverManager.deregisterDriver(stale);
                log.info("Deregistered driver {} previously loaded from a different jar path",
                        driverClassName);
            } catch (SQLException e) {
                log.warn("Could not deregister the stale shim for {}: {}",
                        driverClassName, e.getMessage());
            }
        }
    }

    /** Lists candidate driver class names found in a jar, to help the user fill the form. */
    public List<String> discoverDriverClasses(String jarPath) {
        List<String> found = new ArrayList<>();
        for (String part : jarPath.split(PATH_SPLIT)) {
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
