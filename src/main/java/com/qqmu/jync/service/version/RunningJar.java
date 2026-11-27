package com.qqmu.jync.service.version;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;

/**
 * Where this JVM's jar lives. Both the update check ({@link JarArtifactComparison}) and the
 * one-click upgrade ({@code UpgradeService}) need the file an upgrade would replace, and they
 * must resolve it the same way — a mismatch between "the jar I hashed" and "the jar I swap"
 * would make the artifact comparison describe a file nobody installs.
 */
@Slf4j
public final class RunningJar {

    private RunningJar() {
    }

    /** The jar this class was loaded from; null when unknown. */
    public static Path locate() {
        return locate(RunningJar.class);
    }

    /** The jar (or classes directory) the given class was loaded from; null when unknown. */
    public static Path locate(Class<?> anchor) {
        try {
            var codeSource = anchor.getProtectionDomain().getCodeSource();
            return codeSource == null ? null : fromLocation(codeSource.getLocation());
        } catch (Exception e) {
            log.debug("Cannot resolve jar location: {}", e.toString());
            return null;
        }
    }

    /**
     * Turns a code-source location into the file it runs from. Under {@code java -jar},
     * Spring Boot reports {@code jar:file:/app/jync.jar!/BOOT-INF/classes!/}; {@link Path#of}
     * cannot open a {@code jar:} URI, so unwrap it to the outer jar — the file an upgrade
     * replaces. An exploded run comes back as that directory and the caller rejects it.
     */
    public static Path fromLocation(URL location) throws URISyntaxException {
        if (location == null) {
            return null;
        }
        if (!"jar".equalsIgnoreCase(location.getProtocol())) {
            return Path.of(location.toURI());
        }
        return fromJarSpec(location.toString());
    }

    /**
     * Spring Boot 3.2+ reports a nested URL such as
     * {@code jar:nested:/app/jync.jar/!BOOT-INF/classes!/}; older loaders use
     * {@code jar:file:/app/jync.jar!/BOOT-INF/classes!/}. In both forms the executable jar
     * is the file before the first inner entry separator.
     */
    static Path fromJarSpec(String spec) {
        if (spec == null || !spec.startsWith("jar:")) {
            return null;
        }
        String inner = spec.substring("jar:".length());
        if (inner.startsWith("nested:")) {
            String nested = inner.substring("nested:".length());
            int separator = nested.indexOf("/!");
            return separator <= 0 ? null : pathOrNull(nested.substring(0, separator));
        }
        int separator = spec.indexOf("!/");
        return separator < 0 ? null : pathOrNull(spec.substring("jar:".length(), separator));
    }

    private static Path pathOrNull(String value) {
        try {
            return Path.of(URI.create(value));
        } catch (RuntimeException ignored) {
            try {
                return Path.of(value);
            } catch (RuntimeException notAPath) {
                return null;
            }
        }
    }
}
