package com.synctool.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.repository.DatabaseConfigRepository;
import com.synctool.repository.ProjectRepository;
import com.synctool.service.connection.ConnectionTestService;
import com.synctool.service.connection.DataSourceManager;
import com.synctool.util.CryptoUtil;

/**
 * A preset whose driver is not bundled (GBase / Oscar) must not be savable without a jar path.
 * Letting it through means the failure appears much later — at a scheduled sync, in English,
 * with no form to fix it from.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseConfigServiceValidationTest {

    @Mock private DatabaseConfigRepository repository;
    @Mock private ProjectRepository projectRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private ConnectionTestService connectionTestService;
    @Mock private CryptoUtil cryptoUtil;

    private DatabaseConfigService service;

    @BeforeEach
    void setUp() {
        service = new DatabaseConfigService(repository, projectRepository,
                dataSourceManager, connectionTestService, cryptoUtil);
    }

    private DatabaseConfig preset(DatabaseType type, String jarPath) {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("conn-" + type.name());
        c.setType(type);
        c.setHost("localhost");
        c.setCustomJarPath(jarPath);
        return c;
    }

    @Test
    void gbaseWithoutJarIsRejected() {
        assertThatThrownBy(() -> service.save(preset(DatabaseType.GBASE, "  "), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.driver.jar.required");
        verify(repository, never()).save(any());
    }

    @Test
    void oscarWithoutJarIsRejected() {
        assertThatThrownBy(() -> service.save(preset(DatabaseType.OSCAR, null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.driver.jar.required");
    }

    @Test
    void gbaseWithJarIsAccepted() {
        when(repository.findByName(any())).thenReturn(Optional.empty());
        when(cryptoUtil.encrypt(any())).thenReturn("enc");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.save(preset(DatabaseType.GBASE, "/opt/drivers/gbase.jar"), null);

        verify(repository).save(any());
    }

    @Test
    void bundledDamengNeedsNoJar() {
        when(repository.findByName(any())).thenReturn(Optional.empty());
        when(cryptoUtil.encrypt(any())).thenReturn("enc");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.save(preset(DatabaseType.DM, null), null);

        verify(repository).save(any());
    }
}
