package com.synctool.service.connection;

import org.springframework.util.StringUtils;

import com.synctool.model.DatabaseType;

/**
 * Answers whether a JDBC driver class ships inside the application, i.e. is reachable on
 * the application classpath without a user-supplied jar.
 *
 * <p>The classpath itself is the single source of truth — there is deliberately no hardcoded
 * list of "bundled" drivers. Add or remove a driver dependency in {@code pom.xml} and the
 * connection form follows automatically.
 */
public final class DriverPresence {

    private DriverPresence() {
    }

    /**
     * Whether a connection of {@code type} can only work with a user-supplied jar: the preset
     * driver is not on the classpath, and the driver class was not overridden to one that is.
     * CUSTOM never qualifies — its URL/driver/jar are validated as a package elsewhere.
     */
    public static boolean externalJarRequired(DatabaseType type, String customDriver) {
        if (type == null || type == DatabaseType.CUSTOM) {
            return false;
        }
        if (StringUtils.hasText(customDriver) && isPresent(customDriver.trim())) {
            return false;
        }
        return !isPresent(type.getDriverClassName());
    }

    /** Whether {@code driverClassName} can be loaded from the application classpath. */
    public static boolean isPresent(String driverClassName) {
        if (!StringUtils.hasText(driverClassName)) {
            return false;
        }
        try {
            Class.forName(driverClassName);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
