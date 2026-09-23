package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * The pre-flight check that turns "driver not on classpath" into a localizable message key
 * before any pool is created. Without it the user sees a raw English IllegalStateException
 * sentence, and the background machinery does pointless work first.
 */
@ExtendWith(MockitoExtension.class)
class ConnectionTestServiceTest {

    @Mock
    private DataSourceManager dataSourceManager;

    @Mock
    private Connection connection;

    @Mock
    private java.sql.DatabaseMetaData metaData;

    private ConnectionTestService service() {
        return new ConnectionTestService(dataSourceManager);
    }

    private DatabaseConfig config(DatabaseType type, String jarPath) {
        DatabaseConfig c = new DatabaseConfig();
        c.setId(1L); // saved config: goes through the cached-pool path
        c.setType(type);
        c.setCustomJarPath(jarPath);
        return c;
    }

    @Test
    void gbaseWithoutJarFailsWithTheMessageKeyAndNeverOpensAPool() throws Exception {
        ConnectionTestService.TestResult result = service().test(config(DatabaseType.GBASE, "  "));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("error.driver.jar.required");
        verify(dataSourceManager, never()).getConnection(any());
    }

    @Test
    void bundledTypeWithoutJarProceedsToConnect() throws Exception {
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metaData);

        ConnectionTestService.TestResult result = service().test(config(DatabaseType.DM, null));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void gbaseWithJarPathProceedsToConnect() throws Exception {
        when(dataSourceManager.getConnection(any())).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metaData);

        ConnectionTestService.TestResult result =
                service().test(config(DatabaseType.GBASE, "/opt/drivers/gbase.jar"));

        assertThat(result.isSuccess()).isTrue();
    }

    /**
     * An unsaved config (test from an unsubmitted form) must use a throwaway pool that is
     * closed afterwards — the cached path used to hand out an unclosed Hikari pool per
     * click, leaking its housekeeper thread and socket every time.
     */
    @Test
    void transientConfigUsesThrowawayPoolAndClosesIt() throws Exception {
        com.zaxxer.hikari.HikariDataSource throwaway =
                org.mockito.Mockito.mock(com.zaxxer.hikari.HikariDataSource.class);
        when(dataSourceManager.createTransientDataSource(any())).thenReturn(throwaway);
        when(throwaway.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metaData);

        DatabaseConfig unsaved = new DatabaseConfig();
        unsaved.setType(DatabaseType.DM);

        ConnectionTestService.TestResult result = service().test(unsaved);

        assertThat(result.isSuccess()).isTrue();
        verify(throwaway).close();
        verify(dataSourceManager, never()).getConnection(any());
    }
}
