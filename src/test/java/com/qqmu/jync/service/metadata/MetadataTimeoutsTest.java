package com.qqmu.jync.service.metadata;

import static org.mockito.Mockito.verify;

import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * P2: the configured metadata timeout reaches statements.
 */
class MetadataTimeoutsTest {

    @AfterEach
    void restoreDefault() {
        MetadataTimeouts.configure(60);
    }

    @Test
    void applyUsesTheConfiguredTimeout() throws java.sql.SQLException {
        MetadataTimeouts.configure(42);
        Statement statement = Mockito.mock(Statement.class);

        MetadataTimeouts.apply(statement);

        verify(statement).setQueryTimeout(42);
    }
}
