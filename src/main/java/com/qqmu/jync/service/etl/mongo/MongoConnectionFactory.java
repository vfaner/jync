package com.qqmu.jync.service.etl.mongo;

import org.springframework.stereotype.Component;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Builds the native MongoDB client for one ETL run.
 *
 * <p>The password is decrypted here, after {@link MongoUriBuilder} has rejected any URI that
 * carries one, so the secret only ever exists in memory and never in the stored connection.
 * The client connects lazily: an unreachable server surfaces on the first read, where the
 * source adapter can attribute it to the source side.
 */
@Component
public class MongoConnectionFactory {

    private final CryptoUtil cryptoUtil;

    public MongoConnectionFactory(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    public MongoClient create(DatabaseConfig config) {
        return MongoClients.create(uri(config));
    }

    /** The database the URI path names; the builder guarantees one is present. */
    public String database(DatabaseConfig config) {
        return new ConnectionString(uri(config)).getDatabase();
    }

    public String uri(DatabaseConfig config) {
        String password = cryptoUtil.decrypt(config.getPassword());
        return MongoUriBuilder.build(config, password);
    }
}
