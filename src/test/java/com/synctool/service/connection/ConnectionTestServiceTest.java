package com.synctool.service.connection;

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

import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;

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
}
