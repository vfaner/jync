package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.FormatDescriptionEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.qqmu.jync.model.DatabaseConfig;

/**
 * 一次性流探测:读到一个事件即可证明线格式可解码;行事件是更强的证据,但「这一份
 * binlog 文件暂时没有行事件」不等于不兼容——实测 MariaDB 11.8 刚轮转出的当前文件
 * 只有 FormatDescription / GTID_LIST / CHECKPOINT,行事件都在上一份文件里。
 */
class BinaryLogEventReaderTest {

    private final BinlogClientFactory factory = mock(BinlogClientFactory.class);
    private BinaryLogClient client;
    private BinaryLogClient.EventListener events;
    private BinaryLogClient.LifecycleListener lifecycle;
    private BinaryLogEventReader reader;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(BinaryLogClient.class);
        when(factory.createProbeClient(any(DatabaseConfig.class), anyLong(), anyString(),
                anyLong())).thenReturn(client);
        doAnswer(inv -> {
            events = inv.getArgument(0);
            return null;
        }).when(client).registerEventListener(any(BinaryLogClient.EventListener.class));
        doAnswer(inv -> {
            lifecycle = inv.getArgument(0);
            return null;
        }).when(client).registerLifecycleListener(any(BinaryLogClient.LifecycleListener.class));
        reader = new BinaryLogEventReader(factory);
    }

    private static Event event(EventType type, EventData data) {
        EventHeaderV4 header = new EventHeaderV4();
        header.setEventType(type);
        return new Event(header, data);
    }

    @Test
    void aHeaderEventBeforeTheTimeoutStillProvesTheWireFormat() throws Exception {
        doAnswer(inv -> {
            events.onEvent(event(EventType.FORMAT_DESCRIPTION, new FormatDescriptionEventData()));
            return null;
        }).when(client).connect(anyLong());

        String decoded = reader.readFirstEvent(config(), "mysql-bin.000003", 4L, 1L,
                Duration.ofMillis(120));

        // 读到事件 = 解码器认得这台服务器的字节;只是没等到行事件,不能说成失败。
        assertThat(decoded).contains("FormatDescriptionEventData").contains("no row event");
    }

    @Test
    void aRowEventIsPreferredAndReportedAsSuch() throws Exception {
        doAnswer(inv -> {
            events.onEvent(event(EventType.FORMAT_DESCRIPTION, new FormatDescriptionEventData()));
            events.onEvent(event(EventType.TABLE_MAP, new TableMapEventData()));
            return null;
        }).when(client).connect(anyLong());

        String decoded = reader.readFirstEvent(config(), "mysql-bin.000002", 4L, 1L,
                Duration.ofSeconds(5));

        assertThat(decoded).contains("TableMapEvent");
        assertThat(decoded).doesNotContain("no row event");
    }

    @Test
    void aStreamThatDeliversNothingFails() throws Exception {
        doAnswer(inv -> null).when(client).connect(anyLong());

        assertThatThrownBy(() -> reader.readFirstEvent(config(), "mysql-bin.000003", 4L, 1L,
                Duration.ofMillis(80)))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("decoded no event");
    }

    @Test
    void aDecoderFailureSurfacesTheDecoderMessage() throws Exception {
        doAnswer(inv -> {
            lifecycle.onEventDeserializationFailure(client,
                    new RuntimeException("Unsupported event type 160"));
            return null;
        }).when(client).connect(anyLong());

        assertThatThrownBy(() -> reader.readFirstEvent(config(), "mysql-bin.000003", 4L, 1L,
                Duration.ofMillis(500)))
                .isInstanceOf(CdcDecodeException.class)
                .hasMessageContaining("Unsupported event type 160");
    }

    private DatabaseConfig config() {
        DatabaseConfig config = new DatabaseConfig();
        config.setHost("db.local");
        config.setPort(3306);
        config.setUsername("repl");
        config.setPassword("secret");
        return config;
    }
}
