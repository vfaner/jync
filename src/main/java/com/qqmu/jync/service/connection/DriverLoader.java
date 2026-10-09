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
     * Separates the driver class name from the canonical jar path inside a registration
     * key. U+0001 cannot appear in either part (a class name or file path).
     */
    private static final String KEY_SEPARATOR = "";

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
    public synchronized ClassLoader ensureDriverLoaded(String driverClassName, String jarPath) {
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
                throw new IllegalStateException("error.driver.absent", e);
            }
        }

        URLClassLoader loader = loaderCache.computeIfAbsent(canonical(jarPath),
                key -> buildLoader(jarPath));

        boolean success = false;
        List<ClassLoader> retiredLoaders = List.of();
        try {
            Class<?> driverClass = Class.forName(driverClassName, true, loader);
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            // Many drivers self-register the raw instance from their class's static block.
            // DriverManager hides that registration from callers that cannot see the child
            // loader, but the entry still pins the loader and resurfaces when it is closed;
            // drop it so only our shim remains.
            dropSelfRegistration(driver);
            DriverShim shim = new DriverShim(driver);
            // Retire a shim for the same class that came from a different jar path (the user
            // edited the connection). It must go BEFORE the new registration: DriverManager
            // hands a URL to the first driver that accepts it, so a leftover old shim would
            // keep routing connections through the old jar.
            retiredLoaders = deregisterStaleShims(driverClassName);
            DriverManager.registerDriver(shim);
            registered.put(registeredKey(driverClassName, jarPath), shim);
            log.info("Registered dynamically loaded driver {} from {}", driverClassName, jarPath);
            success = true;
            return loader;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("error.driver.class.not.found", e);
        } catch (SQLException e) {
            throw new IllegalStateException("error.driver.register.failed", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("error.driver.instantiate.failed", e);
        } finally {
            // On failure the freshly built loader (and any loaders orphaned by the partial
            // replacement) would otherwise stay cached with open jar handles and no shim to
            // ever retire them; on success only the retired loaders need closing.
            if (!success) {
                closeIfUnused(loader);
                for (ClassLoader retired : retiredLoaders) {
                    closeIfUnused(retired);
                }
            } else {
                for (ClassLoader retired : retiredLoaders) {
                    closeIfUnused(retired);
                }
            }
        }
    }

    /** Best-effort removal of a driver's own static-block registration. */
    private void dropSelfRegistration(Driver driver) {
        try {
            DriverManager.deregisterDriver(driver);
        } catch (SQLException | RuntimeException e) {
            log.debug("Could not remove the driver's self-registration (it may not have"
                    + " registered): {}", e.getMessage());
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
                throw new IllegalArgumentException("error.driver.jar.nonexistent");
            }
            if (file.isDirectory()) {
                File[] jars = file.listFiles(f -> f.getName().toLowerCase().endsWith(".jar"));
                if (jars == null || jars.length == 0) {
                    throw new IllegalArgumentException("error.driver.directory.empty");
                }
                for (File jar : jars) {
                    urls.add(toUrl(jar));
                }
            } else {
                urls.add(toUrl(file));
            }
        }
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("error.driver.jars.empty");
        }
        // Parent is this application's loader so the driver can see java.sql.*.
        return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
    }

    private URL toUrl(File file) {
        try {
            return file.toURI().toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("error.driver.jar.invalid", e);
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
        return driverClassName + KEY_SEPARATOR + suffix;
    }

    /**
     * Removes and deregisters every shim previously registered for this driver class.
     *
     * @return the class loaders that owned the removed shims, so the caller can close them
     *         once the replacement is registered
     */
    private List<ClassLoader> deregisterStaleShims(String driverClassName) {
        List<ClassLoader> retiredLoaders = new ArrayList<>();
        String prefix = driverClassName + KEY_SEPARATOR;
        for (String key : registered.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            DriverShim stale = registered.remove(key);
            if (stale == null) {
                continue;
            }
            retiredLoaders.add(stale.getDelegate().getClass().getClassLoader());
            try {
                DriverManager.deregisterDriver(stale);
                log.info("Deregistered driver {} previously loaded from a different jar path",
                        driverClassName);
            } catch (SQLException e) {
                log.warn("Could not deregister the stale shim for {}: {}",
                        driverClassName, e.getMessage());
            }
        }
        return retiredLoaders;
    }

    /**
     * Closes a retired loader and drops it from the cache, but only while no remaining shim
     * still uses it: several driver classes can come from the same jar (and hence loader).
     * Closing releases the open jar handles (which lock the file on Windows) and stops the
     * loader leaking per jar replacement.
     */
    private void closeIfUnused(ClassLoader loader) {
        if (!(loader instanceof URLClassLoader urlLoader)) {
            return;
        }
        for (DriverShim shim : registered.values()) {
            if (shim.getDelegate().getClass().getClassLoader() == loader) {
                return;
            }
        }
        loaderCache.values().removeIf(cached -> cached == urlLoader);
        try {
            urlLoader.close();
            log.debug("Closed retired driver class loader");
        } catch (java.io.IOException e) {
            log.warn("Could not close a retired driver class loader: {}", e.getMessage());
        }
    }

    /**
     * Deregisters every shim and closes every loader on context shutdown. Without this a
     * web-app redeploy (or an embedded restart in tests) leaks the loaders and their jar
     * handles along with this bean.
     */
    @javax.annotation.PreDestroy
    public void shutdown() {
        for (DriverShim shim : registered.values()) {
            try {
                DriverManager.deregisterDriver(shim);
            } catch (SQLException e) {
                log.debug("Could not deregister driver on shutdown: {}", e.getMessage());
            }
        }
        registered.clear();
        for (URLClassLoader loader : loaderCache.values()) {
            try {
                loader.close();
            } catch (java.io.IOException e) {
                log.debug("Could not close driver class loader on shutdown: {}", e.getMessage());
            }
        }
        loaderCache.clear();
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
