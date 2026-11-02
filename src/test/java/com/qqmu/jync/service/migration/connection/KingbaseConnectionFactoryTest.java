package com.qqmu.jync.service.migration.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.util.CryptoUtil;

class KingbaseConnectionFactoryTest {

    private KingbaseConnectionFactory factory;

    @BeforeEach
    void setUp() {
        factory = new KingbaseConnectionFactory(mock(CryptoUtil.class));
    }

    @Test
    void parametersAreAppendedToAnUrlWithoutAQuery() {
        assertThat(factory.url(connection("jdbc:kingbase8://db:54321/orders",
                "sslmode=require")))
                .isEqualTo("jdbc:kingbase8://db:54321/orders?sslmode=require");
    }

    @Test
    void parametersAreAppendedToAnUrlThatAlreadyHasAQuery() {
        assertThat(factory.url(connection(
                "jdbc:kingbase8://db:54321/orders?currentSchema=public", "sslmode=require")))
                .isEqualTo("jdbc:kingbase8://db:54321/orders?currentSchema=public"
                        + "&sslmode=require");
    }

    @Test
    void leadingSeparatorsOnTheParametersAreIgnored() {
        assertThat(factory.url(connection("jdbc:kingbase8://db:54321/orders",
                "?&sslmode=require")))
                .isEqualTo("jdbc:kingbase8://db:54321/orders?sslmode=require");
    }

    @Test
    void aTrailingSeparatorOnTheUrlIsNotDuplicated() {
        assertThat(factory.url(connection("jdbc:kingbase8://db:54321/orders?",
                "sslmode=require")))
                .isEqualTo("jdbc:kingbase8://db:54321/orders?sslmode=require");
    }

    @Test
    void aBlankParameterListLeavesTheUrlAlone() {
        assertThat(factory.url(connection("jdbc:kingbase8://db:54321/orders", "  ")))
                .isEqualTo("jdbc:kingbase8://db:54321/orders");
    }

    private static MigrationConnection connection(String baseUrl, String extraParams) {
        MigrationConnection connection = new MigrationConnection();
        connection.setBaseUrl(baseUrl);
        connection.setExtraParams(extraParams);
        return connection;
    }
}
