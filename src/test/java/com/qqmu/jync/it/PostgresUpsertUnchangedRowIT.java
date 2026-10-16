package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.service.converter.PostgresDialect;

/**
 * docker 实测:PostgreSQL 目标上和 Oracle 一样的问题、一样的修法。
 *
 * <p>{@code ON CONFLICT DO UPDATE} 对「值没变的重放」同样报 1 影响行,所以空闲项目每轮都
 * 会记一条 updated。加 {@code WHERE <有差异>} 之后报 0。这个用例还顺带证明了那条 WHERE
 * 在真 PG 上是能解析的 —— 写错位置会让每一次 PG 同步在建语句时就失败。
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresUpsertUnchangedRowIT {

    private static final String DB_USER = "test";
    private static final String DB_PASSWORD = "test";
    private static final String DB_NAME = "test";
    private static final int PG_PORT = 5432;

    private static final GenericContainer<?> PG =
            new GenericContainer<>(DockerImageName.parse("postgres:16"))
                    .withEnv("POSTGRES_USER", DB_USER)
                    .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
                    .withEnv("POSTGRES_DB", DB_NAME)
                    .withExposedPorts(PG_PORT)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofMinutes(3)));

    private static final List<String> COLUMNS = List.of("id", "name", "amount");
    private static final List<String> PK = List.of("id");

    @BeforeAll
    static void start() throws SQLException {
        PG.start();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS upsert_t");
            st.execute("CREATE TABLE upsert_t (id bigint primary key, name varchar(20),"
                    + " amount numeric(10,2))");
        }
    }

    @AfterAll
    static void stop() {
        PG.stop();
    }

    @Test
    void aReplayedRowWithIdenticalValuesAffectsNothing() throws SQLException {
        assertThat(upsert(1, "ann", 10.5)).as("首次插入").isEqualTo(1);
        assertThat(upsert(1, "ann", 10.5))
                .as("值一个字没变的重放，必须报 0 —— 否则每轮日志都有一条假的 updated")
                .isZero();
    }

    @Test
    void aValueAppearingOrDisappearingCountsAsAChange() throws SQLException {
        assertThat(upsert(2, null, 1.0)).isEqualTo(1);
        assertThat(upsert(2, "now-set", 1.0)).as("NULL → 值").isEqualTo(1);
        assertThat(upsert(2, null, 1.0)).as("值 → NULL").isEqualTo(1);
        assertThat(upsert(2, null, 1.0)).as("两边都是 NULL").isZero();
    }

    private static int upsert(long id, String name, double amount) throws SQLException {
        Map<String, Integer> types = new HashMap<>();
        types.put("id", Types.BIGINT);
        types.put("name", Types.VARCHAR);
        types.put("amount", Types.NUMERIC);
        // 生产调用方读目标表字典确认列可比之后才传 true;这里两列都是 bigint/varchar/numeric。
        String sql = new PostgresDialect()
                .getUpsertSql("public", "upsert_t", COLUMNS, PK, types, true);
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            if (name == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, name);
            }
            ps.setBigDecimal(3, BigDecimal.valueOf(amount));
            return ps.executeUpdate();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + PG.getHost() + ":"
                + PG.getMappedPort(PG_PORT) + "/" + DB_NAME, DB_USER, DB_PASSWORD);
    }
}
