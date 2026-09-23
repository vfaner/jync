package com.qqmu.jync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.qqmu.jync.config.SyncProperties;

/**
 * Application entry point.
 *
 * <p>The upgrade path from SyncTool (&lt;= 1.2.x) metadata stores lives in
 * {@link com.qqmu.jync.config.LegacyStoreMigration}, which runs against the effective
 * datasource URL before the context is built.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SyncProperties.class)
public class JyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(JyncApplication.class, args);
    }
}
