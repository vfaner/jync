package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.util.CryptoUtil;

class BinlogClientFactoryTest {

    private final SyncProperties properties = new SyncProperties();
    private final CryptoUtil crypto = new CryptoUtil(properties);
    private final BinlogClientFactory factory =
            new BinlogClientFactory(crypto, properties);

    private DatabaseConfig mysqlSource() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(3L);
        config.setName("src");
        config.setType(DatabaseType.MYSQL);
        config.setHost("db.example.com");
        config.setPort(3307);
        config.setDatabaseName("app");
        config.setUsername("repl");
        config.setPassword(crypto.encrypt("s3cret"));
        return config;
    }

    @Test
    void serverIdIsOffsetPlusProjectId() {
        assertThat(factory.serverIdFor(1L))
                .isEqualTo(properties.getCdc().getServerIdOffset() + 1L);
        assertThat(factory.serverIdFor(42L))
                .isEqualTo(properties.getCdc().getServerIdOffset() + 42L);
    }

    @Test
    void clientCarriesEndpointCredentialsAndServerId() {
        BinaryLogClient client = factory.createClient(mysqlSource(), 42L, null);
        assertThat(client.getServerId())
                .isEqualTo(properties.getCdc().getServerIdOffset() + 42L);
        // keepAlive=true is required to detect NAT-idle / half-open sockets; without it
        // the consumer thread parks on a dead read and never releases the lease.
        // Reconnect policy still lives in CdcConsumer's backoff loop.
        assertThat(client.isKeepAlive()).isTrue();
    }

    @Test
    void gtidPositionWinsOverFilePosition() {
        CdcPosition pos = new CdcPosition();
        pos.setBinlogFile("mysql-bin.000007");
        pos.setBinlogPos(4711L);
        pos.setGtidSet("24bc7850-2c16-11e6-a073-0242ac110002:1-88");
        BinaryLogClient client = factory.createClient(mysqlSource(), 42L, pos);
        assertThat(client.getGtidSet()).isEqualTo(pos.getGtidSet());
        // GTID 模式下不设 file/pos(任务书 B3:GTID 优先)。注意:库自身会把未设置的
        // filename 归一为 ""(setGtidSet 启用 GTID 时空串表示握手时取 master 当前文件),
        // 所以断言"位点里的 file/pos 没有被应用"而非"filename 为 null"。
        assertThat(client.getBinlogFilename()).isIn(null, "");
        assertThat(client.getBinlogPosition()).isNotEqualTo(pos.getBinlogPos());
    }

    @Test
    void filePositionUsedWithoutGtid() {
        CdcPosition pos = new CdcPosition();
        pos.setBinlogFile("mysql-bin.000007");
        pos.setBinlogPos(4711L);
        BinaryLogClient client = factory.createClient(mysqlSource(), 42L, pos);
        assertThat(client.getGtidSet()).isNull();
        assertThat(client.getBinlogFilename()).isEqualTo("mysql-bin.000007");
        assertThat(client.getBinlogPosition()).isEqualTo(4711L);
    }

    @Test
    void nullPortFallsBackToTypeDefault() {
        DatabaseConfig config = mysqlSource();
        config.setPort(null);
        // 不抛异常即可;端口回退逻辑与 DataSourceManager.buildJdbcUrl 一致(:156)
        BinaryLogClient client = factory.createClient(config, 1L, null);
        assertThat(client).isNotNull();
    }
}
