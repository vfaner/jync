package com.qqmu.jync.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * One-time upgrade path from SyncTool (&lt;= 1.2.x), which kept its H2 metadata store under the
 * database name {@code synctool}. Jync's packaged configuration points at {@code ./data/jync}, so
 * on the first start of the new jar the legacy store file next to the configured target is
 * renamed, and existing installations keep their projects, connections and change logs.
 *
 * <p>This runs as an {@link EnvironmentPostProcessor} rather than from {@code main} so the
 * decision is made against the <em>effective</em> {@code spring.datasource.url}, external
 * override files and command-line arguments included. That distinction is load-bearing: a 1.2.x
 * production install following the old README points its URL at an absolute path such as
 * {@code /opt/synctool/data/synctool} and upgrades by dropping in the new jar only. A CWD-based
 * guess would rename the very file its URL still references, and H2 would then create an empty
 * store at the unchanged URL — every project would appear to vanish. Reading the URL instead,
 * such deployments see a database basename that is still {@code synctool} and are left alone
 * entirely; migration only fires when the configured store has a different name, and then only
 * for a {@code synctool.mv.db} sitting in the same directory as the target.
 *
 * <p>No-op on a fresh install, after the first successful start, and when the target store
 * already exists (the legacy file is then left untouched and warned about on every boot, so a
 * failed migration is recoverable rather than silently orphaned). Refuses to touch anything
 * while a {@code synctool.lock.db} suggests an old instance may still be running: on POSIX a
 * rename succeeds even with the file held open, and two H2 writers on one inode corrupt the
 * store.
 *
 * <p>The H2 {@code .trace.db} diagnostic file is deliberately not migrated — it is an optional
 * trace log, and moving it added a second failure path whose error message could only mislead
 * (advising a manual rename of a file that had already been renamed).
 */
public class LegacyStoreMigration implements EnvironmentPostProcessor {

    static final String LEGACY_BASENAME = "synctool";

    private static final String H2_FILE_PREFIX = "jdbc:h2:file:";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path storeBase = configuredStoreBase(environment.getProperty("spring.datasource.url"));
        if (storeBase != null) {
            migrate(storeBase);
        }
    }

    /**
     * Extracts the database file base path from an H2 file URL of the form
     * {@code jdbc:h2:file:<path>;<settings>}. Returns {@code null} for anything this migration
     * must not touch: non-file URLs (in-memory, classpath), home-relative paths that only H2
     * itself can expand, or malformed values — H2 rejects those later with a better error.
     */
    static Path configuredStoreBase(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith(H2_FILE_PREFIX)) {
            return null;
        }
        String path = jdbcUrl.substring(H2_FILE_PREFIX.length());
        int settings = path.indexOf(';');
        if (settings >= 0) {
            path = path.substring(0, settings);
        }
        if (path.isBlank() || path.startsWith("~")) {
            return null;
        }
        try {
            return Paths.get(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Renames a legacy {@code synctool.mv.db} sitting next to the configured store base path to
     * the configured database name. Never throws: a failed migration degrades to a warning and
     * lets the application boot, because the legacy file is preserved either way and a manual
     * rename still recovers the data.
     *
     * <p>Messages go to stdout/stderr because this runs before the logging system is
     * initialised.
     */
    static void migrate(Path storeBase) {
        Path absolute = storeBase.toAbsolutePath();
        Path dir = absolute.getParent();
        if (dir == null) {
            return;
        }
        String basename = absolute.getFileName().toString();
        if (LEGACY_BASENAME.equals(basename)) {
            return; // URL still names the legacy database — this deployment never moved.
        }
        Path legacy = dir.resolve(LEGACY_BASENAME + ".mv.db");
        Path target = dir.resolve(basename + ".mv.db");
        if (!Files.exists(legacy)) {
            return; // Fresh install, or already migrated on an earlier start.
        }
        if (Files.exists(target)) {
            // Warn on every boot: a previous migration attempt failed, or the operator copied
            // the old data directory alongside a store Jync already created. Nothing is lost —
            // the legacy file stays intact — but the operator needs to know which one is live.
            System.out.println("[Jync] 警告 / WARNING: legacy store " + legacy + " exists next to the active "
                    + target + ". Ignoring it. If the legacy file holds the data you need, stop Jync, move "
                    + target + " aside, then rename " + legacy + " to " + target + ".");
            return;
        }
        if (Files.exists(dir.resolve(LEGACY_BASENAME + ".lock.db"))) {
            System.out.println("[Jync] 警告 / WARNING: " + dir.resolve(LEGACY_BASENAME + ".lock.db") + " exists — an "
                    + "old SyncTool instance may still be running. Skipping metadata store migration to avoid "
                    + "two writers on one database. Stop the old instance, delete a stale lock file if any, "
                    + "and restart Jync.");
            return;
        }
        try {
            Files.move(legacy, target);
            System.out.println("[Jync] 已迁移元数据库 " + legacy + " -> " + target
                    + " (migrated metadata store from SyncTool)");
        } catch (IOException e) {
            System.out.println("[Jync] 元数据库迁移失败 / metadata store migration failed: " + e.getMessage()
                    + " —— 请停止 Jync，手动将 " + legacy + " 改名为 " + target + " 后重启；"
                    + "否则本次将以空元数据库启动（旧数据文件仍保留，不会丢失）。");
        }
    }
}
