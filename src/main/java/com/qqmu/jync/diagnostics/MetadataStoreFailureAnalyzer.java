package com.qqmu.jync.diagnostics;

import java.nio.file.Path;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;

import com.qqmu.jync.config.LegacyStoreMigration;

/**
 * Turns Hibernate's generic startup failure "Unable to determine Dialect without JDBC metadata"
 * into an explanation an operator can act on.
 *
 * <p>That message is Hibernate 6/7's fallback whenever it cannot obtain a metadata connection
 * from the configured URL. The actual reason is always the WARN or ERROR logged immediately
 * before it — typically {@code HHH000342} with "Database may be already in use", an H2 file IO
 * exception, a permission error on {@code ./data}, or "Connection refused" for an external
 * database. In Jync the most common trigger is a second process on the same metadata store:
 * a one-click upgrade relaunching the backup jar before the previous JVM had fully exited, or
 * an external supervisor starting a second instance. The advice therefore depends on the store:
 * an H2 file has a lock file to look at, while the MySQL / PostgreSQL / Oracle stores Jync
 * documents have a connection to test and no lock file at all.
 *
 * <p>Registered in {@code META-INF/spring.factories}; Spring Boot resolves the
 * {@link Environment} constructor argument when it instantiates the analyzer.
 */
public class MetadataStoreFailureAnalyzer implements FailureAnalyzer {

    static final String DIALECT_FALLBACK = "Unable to determine Dialect without JDBC metadata";

    private final Environment environment;

    public MetadataStoreFailureAnalyzer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        if (!matches(failure)) {
            return null;
        }
        String url = environment == null ? null : environment.getProperty("spring.datasource.url");
        Path base = LegacyStoreMigration.configuredStoreBase(url);
        if (base == null) {
            // MySQL / PostgreSQL / Oracle (documented external stores) or a malformed or absent
            // URL: there is no H2 lock file to talk about.
            String target = url == null || url.isBlank() ? "默认元数据库" : redact(url);
            return new FailureAnalysis(description(target, false), action(target, false), failure);
        }
        return new FailureAnalysis(description(storeBase(), true), action(storeBase(), true),
                failure);
    }

    private static boolean matches(Throwable failure) {
        Throwable current = failure;
        // A cause chain can loop (proxies wrapping themselves); bound the walk.
        for (int depth = 0; current != null && depth < 64; depth++) {
            String message = current.getMessage();
            if (message != null && message.contains(DIALECT_FALLBACK)) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    private String storeBase() {
        String url = environment == null ? null : environment.getProperty("spring.datasource.url");
        Path base = LegacyStoreMigration.configuredStoreBase(url);
        if (base == null) {
            base = Path.of("./data/jync");
        }
        Path workingDirectory = Path.of(System.getProperty("user.dir", "."));
        return (base.isAbsolute() ? base : workingDirectory.resolve(base)).normalize().toString();
    }

    private static String description(String target, boolean h2FileStore) {
        if (h2FileStore) {
            return "Jync 无法打开元数据库 " + target + "，因此 Hibernate 无法判定数据库方言。\""
                    + DIALECT_FALLBACK + "\" 是 Hibernate 的通用兜底提示：真正原因就在这段堆栈"
                    + "之前的第一条 WARN 或 ERROR 里，通常是 HHH100046 或 HHH000342（拿不到用于"
                    + "查询 JDBC 元数据的连接）、「Database may be already in use」、元数据库文件"
                    + "上的 IO 异常、只读库导致的 H2「Feature not supported」，或外部数据库的"
                    + "「Connection refused」。";
        }
        return "Jync 无法连接元数据库 " + target + "，因此 Hibernate 无法判定数据库方言。\""
                + DIALECT_FALLBACK + "\" 是 Hibernate 的通用兜底提示：真正原因就在这段堆栈"
                + "之前的第一条 WARN 或 ERROR 里，通常是 HHH100046 或 HHH000342（拿不到用于"
                + "查询 JDBC 元数据的连接）、数据库未启动或地址端口不对的「Connection refused」、"
                + "账号或口令错误的认证失败、目标库尚未创建，或网络与防火墙拦截。";
    }

    private static String action(String target, boolean h2FileStore) {
        if (h2FileStore) {
            return "1. 检查是否有另一个 Jync 进程在使用同一数据目录：H2 锁文件为 " + target
                    + ".lock.db。请停止另一个实例——两个 Jync 实例共用一个元数据库还会重复调度"
                    + "任务、重复消费 CDC——或为当前实例单独配置 spring.datasource.url。\n"
                    + "2. 若没有 Jync 在运行，且锁文件是进程被强制结束后的残留，删除 " + target
                    + ".lock.db。\n"
                    + "3. 确认 " + target + " 所在目录对运行 Jync 的用户可写，且磁盘未满。\n"
                    + "4. 如果刚用一键升级，请查看 jar 旁的 logs/jync-restart.log，确认旧进程已"
                    + "退出后新进程才启动。";
        }
        return "1. 确认 spring.datasource.url 与账号口令正确、数据库已启动，并用客户端从本机"
                + "连一次 " + target + "；「Connection refused」是地址、端口或数据库未启动，"
                + "「Access denied / password authentication failed」是账号口令问题。\n"
                + "2. 确认目标库已创建（MySQL 示例：CREATE DATABASE jync DEFAULT CHARACTER SET "
                + "utf8mb4;）。\n"
                + "3. 多实例共用一个外部元数据库是支持的，不要按 H2 锁文件问题处理。\n"
                + "4. 如果刚用一键升级，请查看 jar 旁的 logs/jync-restart.log，确认旧进程已"
                + "退出后新进程才启动。";
    }

    /** A JDBC URL can carry credentials; the analysis is written to the log. */
    private static String redact(String url) {
        return url.replaceAll("(?i)(password|pwd)=[^;&]*", "$1=***");
    }
}
