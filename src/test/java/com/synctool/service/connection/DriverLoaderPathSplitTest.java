package com.synctool.service.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Detection and actual loading must parse multi-jar paths with the same separators —
 * including {@code :} on Unix — otherwise a classpath that loads fine silently discovers
 * no drivers.
 */
class DriverLoaderPathSplitTest {

    private File jarWithDriver(@TempDir File dir, String name, String driverClass) throws Exception {
        File jar = new File(dir, name);
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            out.write(driverClass.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void discoversAcrossColonSeparatedPaths(@TempDir File dir) throws Exception {
        File a = jarWithDriver(dir, "a.jar", "com.example.DriverA");
        File b = jarWithDriver(dir, "b.jar", "com.example.DriverB");

        List<String> drivers = new DriverLoader()
                .discoverDriverClasses(a.getAbsolutePath() + ":" + b.getAbsolutePath());

        assertThat(drivers).containsExactly("com.example.DriverA", "com.example.DriverB");
    }
}
