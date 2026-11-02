package com.qqmu.jync.service.migration.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mongodb.MongoClientSettings;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.util.CryptoUtil;

@ExtendWith(MockitoExtension.class)
class MongoMigrationClientFactoryTest {

    @Mock private CryptoUtil cryptoUtil;

    private MongoMigrationClientFactory factory;

    @BeforeEach
    void setUp() {
        factory = new MongoMigrationClientFactory(cryptoUtil);
    }

    private MigrationConnection connection(String url, String database) {
        MigrationConnection connection = new MigrationConnection();
        connection.setKind(MigrationConnectionKind.MONGODB);
        connection.setBaseUrl(url);
        connection.setDatabaseName(database);
        connection.setAuthType(MigrationConnectionAuthType.BASIC);
        connection.setUsername("app");
        connection.setConnectTimeoutMs(5000);
        connection.setRequestTimeoutMs(60000);
        connection.setVerifyTls(true);
        return connection;
    }

    @Test
    void theUriCarriesTheDecryptedPasswordButTheStoredUrlDoesNot() {
        MigrationConnection connection = connection("mongodb://mongo.internal:27017", "orders");
        connection.setPasswordEnc("enc:pw");
        when(cryptoUtil.decrypt("enc:pw")).thenReturn("s3cret");

        String uri = factory.uri(connection);

        assertThat(uri).isEqualTo("mongodb://app:s3cret@mongo.internal:27017/orders");
        assertThat(connection.getBaseUrl()).doesNotContain("s3cret");
    }

    @Test
    void extraOptionsAreAppendedToTheUri() {
        MigrationConnection connection = connection("mongodb://mongo.internal:27017", "orders");
        connection.setExtraParams("authSource=admin&retryWrites=true");

        assertThat(factory.uri(connection, "pw")).isEqualTo(
                "mongodb://app:pw@mongo.internal:27017/orders?authSource=admin&retryWrites=true");
    }

    @Test
    void aPasswordInsideTheUriIsRefused() {
        MigrationConnection connection =
                connection("mongodb://app:plain@mongo.internal:27017", "orders");

        assertThatThrownBy(() -> factory.uri(connection, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.passwordInUrl");
    }

    @Test
    void theDatabaseComesFromTheUriPathOrTheField() {
        assertThat(factory.database(connection("mongodb://mongo.internal:27017/orders", null)))
                .isEqualTo("orders");
        assertThat(factory.database(connection("mongodb://mongo.internal:27017", "orders")))
                .isEqualTo("orders");
    }

    @Test
    void aMissingDatabaseIsRejected() {
        assertThatThrownBy(() -> factory.database(
                connection("mongodb://mongo.internal:27017", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.database.required");
    }

    @Test
    void theConnectionTimeoutsBecomeDriverTimeouts() {
        MongoClientSettings settings = factory.settings(
                connection("mongodb://mongo.internal:27017", "orders"), "pw");

        assertThat(settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS))
                .isEqualTo(5000);
        assertThat(settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS))
                .isEqualTo(60000);
        assertThat(settings.getClusterSettings()
                .getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5000);
    }
}
