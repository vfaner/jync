package com.qqmu.jync.service.migration.es;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.util.CryptoUtil;

/** Decrypts a stored connection exactly once and hands the client plain credentials. */
@Component
public class SearchHttpClientFactory {

    private final CryptoUtil cryptoUtil;

    public SearchHttpClientFactory(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    public SearchHttpClient create(MigrationConnection connection) {
        return new SearchHttpClient(connection,
                cryptoUtil.decrypt(connection.getPasswordEnc()),
                cryptoUtil.decrypt(connection.getApiKeyEnc()));
    }
}
