package com.qqmu.jync.service.etl.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * The native MongoDB connection is expressed as a standard Mongo URI, not a JDBC URL. The
 * builder owns the one place where host/port/database/credentials/options become that URI,
 * so the connection form, the save-time validator and the runtime client cannot drift.
 */
class MongoUriBuilderTest {

    private static DatabaseConfig config() {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("mongo");
        c.setType(DatabaseType.MONGODB_NATIVE);
        c.setRole(ConnectionRole.SOURCE);
        c.setHost("localhost");
        c.setDatabaseName("shop");
        return c;
    }

    @Test
    void buildsAGeneratedUriFromHostPortAndDatabase() {
        assertThat(MongoUriBuilder.build(config(), null))
                .isEqualTo("mongodb://localhost:27017/shop");
    }

    @Test
    void usesTheConfiguredPortInsteadOfTheDefault() {
        DatabaseConfig c = config();
        c.setPort(27018);

        assertThat(MongoUriBuilder.build(c, null)).isEqualTo("mongodb://localhost:27018/shop");
    }

    @Test
    void injectsEncodedCredentials() {
        DatabaseConfig c = config();
        c.setUsername("a b");
        c.setPassword("encrypted");

        assertThat(MongoUriBuilder.build(c, "p@ss:w/rd"))
                .isEqualTo("mongodb://a%20b:p%40ss%3Aw%2Frd@localhost:27017/shop");
    }

    @Test
    void bracketsAnIpv6Host() {
        DatabaseConfig c = config();
        c.setHost("::1");

        assertThat(MongoUriBuilder.build(c, null)).isEqualTo("mongodb://[::1]:27017/shop");
    }

    @Test
    void encodesTheDatabasePathSegment() {
        DatabaseConfig c = config();
        c.setDatabaseName("my db");

        assertThat(MongoUriBuilder.build(c, null)).isEqualTo("mongodb://localhost:27017/my%20db");
    }

    @Test
    void appendsExtraOptionsToAGeneratedUri() {
        DatabaseConfig c = config();
        c.setExtraParams("authSource=admin&replicaSet=rs0");

        assertThat(MongoUriBuilder.build(c, null)).isEqualTo(
                "mongodb://localhost:27017/shop?authSource=admin&replicaSet=rs0");
    }

    @Test
    void aCustomUriKeepsItsHostPathAndQuery() {
        DatabaseConfig c = config();
        c.setCustomUrl("mongodb://mongo.internal:27018/warehouse?replicaSet=rs0");

        assertThat(MongoUriBuilder.build(c, null))
                .isEqualTo("mongodb://mongo.internal:27018/warehouse?replicaSet=rs0");
    }

    @Test
    void aCustomUriWithoutPathTakesTheConfiguredDatabase() {
        DatabaseConfig c = config();
        c.setCustomUrl("mongodb://mongo.internal:27018");

        assertThat(MongoUriBuilder.build(c, null))
                .isEqualTo("mongodb://mongo.internal:27018/shop");
    }

    @Test
    void aCustomUriPathWinsOverTheConfiguredDatabase() {
        DatabaseConfig c = config();
        c.setCustomUrl("mongodb://mongo.internal/warehouse");

        assertThat(MongoUriBuilder.build(c, null)).isEqualTo("mongodb://mongo.internal/warehouse");
    }

    @Test
    void aCustomUriWithoutAnyDatabaseIsRejected() {
        DatabaseConfig c = config();
        c.setDatabaseName(null);
        c.setCustomUrl("mongodb://mongo.internal:27018");

        assertThatThrownBy(() -> MongoUriBuilder.build(c, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.database.required");
    }

    @Test
    void aCustomUriGainsTheConfiguredCredentialsWhenItHasNoUserInfo() {
        DatabaseConfig c = config();
        c.setCustomUrl("mongodb://mongo.internal/shop");
        c.setUsername("alice");
        c.setPassword("encrypted");

        assertThat(MongoUriBuilder.build(c, "secret"))
                .isEqualTo("mongodb://alice:secret@mongo.internal/shop");
    }

    @Test
    void aUsernameOnlyCustomUriStillReceivesThePasswordFromTheField() {
        DatabaseConfig c = config();
        c.setCustomUrl("mongodb://alice@mongo.internal/shop?replicaSet=rs0");
        c.setUsername("alice");

        // The help text tells operators to keep the secret in the password field, and a
        // username-only userinfo is exactly the replica-set / SRV form it recommends.
        assertThat(MongoUriBuilder.build(c, "p@ss"))
                .isEqualTo("mongodb://alice:p%40ss@mongo.internal/shop?replicaSet=rs0");
        assertThat(MongoUriBuilder.preview(c))
                .isEqualTo("mongodb://alice:****@mongo.internal/shop?replicaSet=rs0");
    }

    @Test
    void aPasswordInsideTheCustomUriIsRejected() {
        DatabaseConfig c = config();
        c.setCustomUrl("mongodb://alice:secret@mongo.internal/shop");

        assertThatThrownBy(() -> MongoUriBuilder.build(c, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.passwordInUrl");
    }

    @Test
    void aJdbcPrefixedUriIsRejected() {
        DatabaseConfig c = config();
        c.setCustomUrl("jdbc:mongodb://mongo.internal/shop");

        assertThatThrownBy(() -> MongoUriBuilder.build(c, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.invalid");
    }

    @Test
    void anSrvUriIsAcceptedWithoutAnExplicitPortAndRejectedWithOne() {
        DatabaseConfig srv = config();
        srv.setCustomUrl("mongodb+srv://cluster0.example.net/shop");
        assertThat(MongoUriBuilder.build(srv, null))
                .isEqualTo("mongodb+srv://cluster0.example.net/shop");

        DatabaseConfig withPort = config();
        withPort.setCustomUrl("mongodb+srv://cluster0.example.net:27017/shop");
        assertThatThrownBy(() -> MongoUriBuilder.build(withPort, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.invalid");
    }

    @Test
    void extraOptionsNeverOverrideOneTheCustomUriAlreadyCarries() {
        DatabaseConfig c = config();
        c.setCustomUrl("mongodb://mongo.internal/shop?authSource=admin");
        c.setExtraParams("authSource=other&tls=true");

        assertThat(MongoUriBuilder.build(c, null))
                .isEqualTo("mongodb://mongo.internal/shop?authSource=admin&tls=true");
    }

    @Test
    void credentialsAndMalformedOptionNamesAreRejected() {
        DatabaseConfig userOption = config();
        userOption.setExtraParams("user=alice");
        assertThatThrownBy(() -> MongoUriBuilder.build(userOption, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.option.invalid");

        DatabaseConfig badKey = config();
        badKey.setExtraParams("1bad=true");
        assertThatThrownBy(() -> MongoUriBuilder.build(badKey, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.option.invalid");
    }

    @Test
    void previewMasksThePasswordAndNeverLeaksIt() {
        DatabaseConfig c = config();
        c.setUsername("alice");
        c.setPassword("encrypted");

        String preview = MongoUriBuilder.preview(c);

        assertThat(preview).isEqualTo("mongodb://alice:****@localhost:27017/shop");
        assertThat(preview).doesNotContain("encrypted");
    }

    @Test
    void previewOfACustomUriAlsoMasksInjectedCredentials() {
        DatabaseConfig c = config();
        c.setCustomUrl("mongodb://mongo.internal/shop");
        c.setUsername("alice");
        c.setPassword("encrypted");

        assertThat(MongoUriBuilder.preview(c))
                .isEqualTo("mongodb://alice:****@mongo.internal/shop");
    }

    @Test
    void validateAcceptsAValidConfigAndRejectsAStructuralProblem() {
        MongoUriBuilder.validate(config());

        DatabaseConfig bad = config();
        bad.setCustomUrl("http://mongo.internal/shop");
        assertThatThrownBy(() -> MongoUriBuilder.validate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.uri.invalid");
    }
}
