package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.github.luben.zstd.ZstdInputStream;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TransactionPayloadEventData;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;

/**
 * Real MySQL 8.0.20+ with {@code binlog_transaction_compression=ON}: the server wraps the
 * transaction in a TRANSACTION_PAYLOAD, and Jync's own deserializer (signed TIME2) must be
 * able to unpack the zstd payload into the expected inner events. This is the end-to-end
 * check that the unit-level payload dispatch test cannot provide.
 *
 * <pre>
 * JYNC_MYSQL_IT_URL=jdbc:mysql://127.0.0.1:3307/test \
 * JYNC_MYSQL_IT_USER=root JYNC_MYSQL_IT_PASSWORD=... \
 * mvn -q test -Dtest=MysqlCompressedBinlogIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "JYNC_MYSQL_IT_URL", matches = ".+")
class MysqlCompressedBinlogIT {

    private static final String TABLE = "jync_compressed_probe";

    @Test
    void theServerSendsAPayloadJyncsDeserializerCanUnpack() throws Exception {
        String url = System.getenv("JYNC_MYSQL_IT_URL");
        String user = System.getenv("JYNC_MYSQL_IT_USER");
        String password = System.getenv("JYNC_MYSQL_IT_PASSWORD");
        assumeTrue(user != null && password != null, "JYNC_MYSQL_IT_USER/PASSWORD are required");
        URI uri = URI.create(url.substring("jdbc:".length()));
        int port = uri.getPort() < 0 ? 3306 : uri.getPort();

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(true);
            assumeTrue(supportsCompression(conn), "server has no binlog_transaction_compression");
            exec(conn, "DROP TABLE IF EXISTS " + TABLE);
            exec(conn, "CREATE TABLE " + TABLE + " (id BIGINT PRIMARY KEY, payload VARCHAR(1024))");
            try {
                String[] at = currentPosition(conn);
                CountDownLatch seen = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                List<EventType> inner = new CopyOnWriteArrayList<>();

                BinaryLogClient client = new BinaryLogClient(uri.getHost(), port, user, password);
                client.setServerId(4_294_967_200L);
                client.setBinlogFilename(at[0]);
                client.setBinlogPosition(Long.parseLong(at[1]));
                client.setEventDeserializer(CdcEventDeserializers.newClientDeserializer());
                client.registerEventListener(event -> {
                    if (event.getHeader().getEventType() != EventType.TRANSACTION_PAYLOAD) {
                        return;
                    }
                    try {
                        TransactionPayloadEventData data = event.getData();
                        assertThat(data.getCompressionType()).isZero();
                        EventDeserializer deserializer = CdcEventDeserializers.newClientDeserializer();
                        try (ZstdInputStream zstd = new ZstdInputStream(
                                new ByteArrayInputStream(data.getPayload()));
                             ByteArrayInputStream in = new ByteArrayInputStream(zstd)) {
                            Event innerEvent;
                            while ((innerEvent = deserializer.nextEvent(in)) != null) {
                                inner.add(innerEvent.getHeader().getEventType());
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        seen.countDown();
                    }
                });
                Thread reader = new Thread(() -> {
                    try {
                        client.connect();
                    } catch (Exception e) {
                        failure.compareAndSet(null, e);
                        seen.countDown();
                    }
                }, "compressed-binlog-probe");
                reader.setDaemon(true);
                reader.start();
                waitForConnection(client);

                // One transaction large enough that MySQL actually compresses it.
                try (Statement st = conn.createStatement()) {
                    st.execute("SET SESSION binlog_transaction_compression=ON");
                }
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + TABLE + " (id, payload) VALUES (?, ?)")) {
                    String payload = "x".repeat(1024);
                    for (int i = 1; i <= 200; i++) {
                        ps.setLong(1, i);
                        ps.setString(2, payload);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                conn.setAutoCommit(true);

                assertThat(seen.await(20, TimeUnit.SECONDS))
                        .as("a TRANSACTION_PAYLOAD must arrive for a compressed transaction")
                        .isTrue();
                client.disconnect();
                reader.join(5_000L);
                assertThat(failure.get()).isNull();
                assertThat(inner).contains(EventType.TABLE_MAP, EventType.XID);
                assertThat(inner).anyMatch(type -> type == EventType.EXT_WRITE_ROWS
                        || type == EventType.WRITE_ROWS);
            } finally {
                conn.setAutoCommit(true);
                exec(conn, "DROP TABLE IF EXISTS " + TABLE);
            }
        }
    }

    private static boolean supportsCompression(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT @@binlog_transaction_compression")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private static String[] currentPosition(Connection conn) throws SQLException {
        for (String sql : new String[]{"SHOW BINARY LOG STATUS", "SHOW MASTER STATUS"}) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                if (rs.next()) {
                    return new String[]{rs.getString("File"),
                            String.valueOf(rs.getLong("Position"))};
                }
            } catch (SQLException ignored) {
                // 8.0.22+ has the first form, older servers only the second.
            }
        }
        throw new IllegalStateException("no binary log position available");
    }

    private static void waitForConnection(BinaryLogClient client) throws InterruptedException {
        for (int i = 0; i < 100 && !client.isConnected(); i++) {
            Thread.sleep(50L);
        }
        assumeTrue(client.isConnected(), "binlog client did not connect");
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
