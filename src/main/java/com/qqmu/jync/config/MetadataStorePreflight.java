package com.qqmu.jync.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Answers one question before Hibernate starts: will the running user be able to open the
 * configured H2 file store at all?
 *
 * <p>The failure this exists for leaves almost no trace: the data directory is readable and the
 * {@code .mv.db} / {@code .trace.db} files inside it may even be world-writable, but the running
 * user cannot create a new entry in the directory. H2 then cannot create
 * {@code <store>.lock.db}, fails with "Could not save properties ...lock.db" and deletes nothing
 * (there is nothing to delete), so the operator sees a startup failure and no lock file — the
 * opposite of what lock contention looks like. The same happens when the store file itself is
 * read-only: H2 silently switches to read-only mode, and {@code AUTO_SERVER=TRUE} then refuses
 * to open it at all.
 *
 * <p>Like {@link MetadataStoreLock}, this only inspects the filesystem: it never opens, locks or
 * modifies the store, and never stops the application. It exists so the operator gets the real
 * reason in the log <em>before</em> Hibernate's generic "Unable to determine Dialect" line.
 */
final class MetadataStorePreflight {

    private MetadataStorePreflight() {
    }

    /** An operator-facing startup problem for the configured store, or empty when there is none. */
    static Optional<String> startupProblem(String jdbcUrl, Path workingDirectory) {
        Path base = LegacyStoreMigration.configuredStoreBase(jdbcUrl);
        if (base == null) {
            return Optional.empty();
        }
        Path absolute = (base.isAbsolute() ? base : workingDirectory.resolve(base)).normalize();
        Path dir = absolute.getParent();
        if (dir == null) {
            return Optional.empty();
        }
        Path data = Path.of(absolute + ".mv.db");
        if (Files.isRegularFile(data) && !Files.isWritable(data)) {
            return Optional.of("元数据库文件 " + data + " 对当前运行用户不可写。H2 会以只读模式"
                    + "打开它，而 Jync 的配置带 AUTO_SERVER=TRUE，只读库无法启用该模式，启动会直接"
                    + "失败。请把文件所属用户改成运行 Jync 的用户（例如 chown <运行用户> " + data
                    + "）并确认磁盘未满，然后重启。");
        }
        if (Files.isDirectory(dir) && !Files.isWritable(dir)) {
            return Optional.of("元数据库目录 " + dir + " 对当前运行用户不可写：H2 打开库时必须"
                    + "在该目录里新建 " + absolute.getFileName() + ".lock.db（以及临时文件），"
                    + "新建失败会以「Could not save properties ...lock.db」和 Hibernate 的"
                    + "「Unable to determine Dialect without JDBC metadata」结束启动，而且不会"
                    + "留下锁文件。请把该目录的所有者改成运行 Jync 的用户（例如 chown <运行用户> "
                    + dir + "）；容器部署请确认挂载目录属于镜像中的 jync 用户（uid 10001）。");
        }
        return Optional.empty();
    }
}
