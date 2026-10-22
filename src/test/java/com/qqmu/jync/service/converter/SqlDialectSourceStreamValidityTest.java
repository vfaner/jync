package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * mssql-jdbc closes the stream of the previous column as soon as the next column's stream
 * getter is called on the same row (measured; {@code SqlServerToOracleLobIT} reproduces it
 * with an NVARCHAR(MAX) followed by a VARBINARY(MAX)). Every other bundled driver keeps
 * the streams independent, which is what lets the one-shot read path stay lazy.
 */
class SqlDialectSourceStreamValidityTest {

    @Test
    void sqlServerStreamsDoNotSurviveReadingTheNextColumn() {
        assertThat(new SqlServerDialect().streamsValidAcrossColumns()).isFalse();
    }

    @Test
    void otherFamiliesKeepTheirStreamsValidAcrossColumns() {
        assertThat(new OracleDialect().streamsValidAcrossColumns()).isTrue();
        assertThat(new PostgresDialect().streamsValidAcrossColumns()).isTrue();
        assertThat(new OpenGaussDialect().streamsValidAcrossColumns()).isTrue();
        assertThat(new MySqlDialect().streamsValidAcrossColumns()).isTrue();
        assertThat(new GenericSqlDialect().streamsValidAcrossColumns()).isTrue();
    }
}
