package com.qqmu.jync.service.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves a MySQL-family server's major version, because 5.7 and 8.0 accept different SQL:
 * 5.7 has no window functions (the full load's key sampling query is a syntax error there) and
 * no expression defaults. The version is read from the server, never from the preset — a
 * connection named "MySQL" may point at either release, and a preset says nothing about the
 * server behind it.
 *
 * <p>Only the two products whose release trains this project distinguishes get probed. The
 * MySQL-protocol forks (Doris, TiDB, the compatible presets) have their own types and keep the
 * attempt-then-fall-back behavior: resolving a version for them would change SQL that is
 * already handled. A fork connected through the plain {@code MYSQL} preset is probed, and will
 * report its MySQL-compatibility version (TiDB says 5.7) — pick the fork's own preset.
 *
 * <p>Results are cached per configuration revision so a two-second polling cycle does not open
 * an extra connection per cycle; a server upgraded in place keeps the cached answer until the
 * connection is edited or the process restarts (the stale answer only costs parallelism or a
 * dropped default, never a wrong write). A failed probe is <em>not</em> cached: the server may
 * only be briefly unreachable, and the next cycle should try again.
 */
@Component
@Slf4j
public class ServerVersionResolver {

    /** Roughly the number of connections one operator keeps; a guard, not a working set. */
    private static final int MAX_CACHED = 256;

    private final DataSourceManager dataSourceManager;
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();

    public ServerVersionResolver(DataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    /**
     * @return the server's major version (MySQL 5/8, MariaDB 10/11), or {@code null} when the
     *         product is not probed or the server could not answer — callers then keep their
     *         portable behavior
     */
    public Integer majorVersion(DatabaseConfig config) {
        if (config == null || !probes(config.getType())) {
            return null;
        }
        String key = cacheKey(config);
        Integer cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Integer resolved = probe(config);
        if (resolved != null) {
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(key, resolved);
        }
        return resolved;
    }

    private static boolean probes(DatabaseType type) {
        return type == DatabaseType.MYSQL || type == DatabaseType.MARIADB;
    }

    private static String cacheKey(DatabaseConfig config) {
        return config.getId() + ":" + config.getUpdatedAt();
    }

    private Integer probe(DatabaseConfig config) {
        if (dataSourceManager.isPoolSaturated(config)) {
            // A version hint is not worth a fifteen-second Hikari timeout, nor a seat a
            // running chunk worker needs. Unknown is safe: the caller attempts the sampling
            // query and falls back, exactly as it did before this resolver existed.
            log.debug("Pool for '{}' is saturated; skipping the version probe", config.getName());
            return null;
        }
        try (Connection conn = dataSourceManager.getConnection(config)) {
            int major = conn.getMetaData().getDatabaseMajorVersion();
            return major > 0 ? major : null;
        } catch (SQLException | RuntimeException e) {
            log.debug("Could not resolve the server version of '{}': {}",
                    config.getName(), e.getMessage());
            return null;
        }
    }
}
