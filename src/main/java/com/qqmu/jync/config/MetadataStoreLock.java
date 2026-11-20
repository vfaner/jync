package com.qqmu.jync.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Read-only inspection of the H2 lock file that sits next to the metadata store
 * ({@code data/jync.lock.db} for the packaged {@code jdbc:h2:file:./data/jync}).
 *
 * <p>H2 deletes the lock file on a clean shutdown, so its presence at startup means either a
 * second Jync process still holds the store or a previous one was killed. With
 * {@code AUTO_SERVER=TRUE} the file also names the holder's TCP endpoint
 * ({@code server=host:port}), which can be probed to tell the two apart. Two Jync instances
 * on one metadata store are not supported — both would schedule the same jobs and consume the
 * same CDC streams — and the second one is the process that dies at startup with Hibernate's
 * generic "Unable to determine Dialect without JDBC metadata".
 *
 * <p>Nothing is opened, locked or deleted here: the check runs before the context and must not
 * change what H2 will find when the datasource comes up.
 */
final class MetadataStoreLock {

    /** Connect probe, injectable so tests never touch the network. */
    interface Probe {
        boolean answers(String host, int port);
    }

    /**
     * @param lockFile       the {@code <store>.lock.db} file that was found
     * @param serverAddress  the {@code server=} value, or {@code null} when the lock names none
     * @param serverAnswered whether a TCP endpoint at {@code serverAddress} accepted a connection
     */
    record Evidence(Path lockFile, String serverAddress, boolean serverAnswered) {
    }

    private MetadataStoreLock() {
    }

    /** The lock file for the configured store, or empty when it does not exist. */
    static Optional<Evidence> inspect(String jdbcUrl, Path workingDirectory, Probe probe) {
        Path base = LegacyStoreMigration.configuredStoreBase(jdbcUrl);
        if (base == null) {
            return Optional.empty();
        }
        Path absolute = (base.isAbsolute() ? base : workingDirectory.resolve(base)).normalize();
        Path lockFile = absolute.resolveSibling(absolute.getFileName() + ".lock.db");
        if (!Files.isRegularFile(lockFile)) {
            return Optional.empty();
        }
        String serverAddress = serverAddress(lockFile);
        HostAndPort address = HostAndPort.parse(serverAddress);
        boolean answered = address != null && probe.answers(address.host(), address.port());
        return Optional.of(new Evidence(lockFile, serverAddress, answered));
    }

    /** A one-line operator warning, or empty when no lock file is present. */
    static Optional<String> startupWarning(String jdbcUrl, Path workingDirectory, Probe probe) {
        return inspect(jdbcUrl, workingDirectory, probe).map(MetadataStoreLock::describe);
    }

    private static String describe(Evidence evidence) {
        if (evidence.serverAnswered()) {
            return "检测到另一个 Jync 实例正在使用同一元数据库：锁文件 "
                    + evidence.lockFile() + " 登记的 H2 TCP 服务 " + evidence.serverAddress()
                    + " 仍在响应。两个 Jync 实例共用一个元数据库不受支持（会重复调度任务、"
                    + "重复消费同一批 CDC 流），请先停止另一个实例，或为当前实例单独配置"
                    + " spring.datasource.url。";
        }
        String endpoint = evidence.serverAddress() == null
                ? "未登记 H2 服务地址，可能未开启 AUTO_SERVER"
                : "登记的 H2 服务 " + evidence.serverAddress() + " 未响应";
        return "发现元数据库锁文件 " + evidence.lockFile() + "（" + endpoint
                + "）。这多半是进程被强制结束（kill -9 或崩溃）后留下的残留锁。"
                + "若启动失败并提示「Database may be already in use」或「Unable to determine"
                + " Dialect without JDBC metadata」，请先确认没有 Jync 进程在运行，"
                + "再删除该文件。";
    }

    private static String serverAddress(Path lockFile) {
        // H2 writes the lock file with Properties.store, so read it the same way rather than
        // guessing at escaping (H2's own checkServer does exactly this).
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(lockFile)) {
            properties.load(in);
        } catch (IOException e) {
            return null;
        }
        String value = properties.getProperty("server");
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Real probe: a short connect is enough; H2's handshake is not attempted. */
    static boolean serverAnswers(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private record HostAndPort(String host, int port) {

        static HostAndPort parse(String value) {
            if (value == null) {
                return null;
            }
            String text = value.startsWith("tcp://") ? value.substring("tcp://".length()) : value;
            int colon = text.lastIndexOf(':');
            if (colon <= 0 || colon == text.length() - 1) {
                return null;
            }
            String host = text.substring(0, colon);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            try {
                int port = Integer.parseInt(text.substring(colon + 1));
                return port > 0 && port <= 65535 ? new HostAndPort(host, port) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
