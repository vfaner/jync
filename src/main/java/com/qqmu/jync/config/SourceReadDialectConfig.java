package com.qqmu.jync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.PollingSourceDialect;
import com.qqmu.jync.service.converter.SqlDialect;

/**
 * Read-only dialects for the six analytic / search engines that take part in real-time sync as
 * polling sources.
 *
 * <p>These beans extend {@code GenericSqlDialect} so they inherit its whole ANSI implementation.
 * That makes them candidates for any {@code GenericSqlDialect} injection point, so
 * {@code SqlDialectFactory} pins its fallback argument with
 * {@code @Qualifier("genericSqlDialect")} — Spring matches a factory-method bean by its runtime
 * type, not by the declared return type.
 *
 * <p>The offset flag mirrors each product's documented grammar. Hive and Impala do document an
 * offset form, but only for server releases this build cannot verify, so they stay on the
 * single streamed read — the same rule {@code SqlDialect#supportsOffsetPaging()} already states
 * for every product.
 */
@Configuration
public class SourceReadDialectConfig {

    @Bean
    public SqlDialect dorisReadDialect() {
        return new PollingSourceDialect(DatabaseType.DORIS, '`', true);
    }

    @Bean
    public SqlDialect clickHouseReadDialect() {
        return new PollingSourceDialect(DatabaseType.CLICKHOUSE, '`', true);
    }

    @Bean
    public SqlDialect hiveReadDialect() {
        return new PollingSourceDialect(DatabaseType.HIVE, '`', false);
    }

    @Bean
    public SqlDialect impalaReadDialect() {
        return new PollingSourceDialect(DatabaseType.IMPALA, '`', false);
    }

    @Bean
    public SqlDialect elasticsearchReadDialect() {
        return new PollingSourceDialect(DatabaseType.ELASTICSEARCH, '"', false);
    }

    @Bean
    public SqlDialect mongoAtlasReadDialect() {
        return new PollingSourceDialect(DatabaseType.MONGODB, '"', false);
    }
}
