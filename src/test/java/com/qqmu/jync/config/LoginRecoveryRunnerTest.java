package com.qqmu.jync.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.auth.oa.OaAuthConfigService;

/** The rescue switch only does anything when it is set; the default boot must not touch the row. */
class LoginRecoveryRunnerTest {

    private final OaAuthConfigService configService = mock(OaAuthConfigService.class);

    @Test
    void whenTheSwitchIsOnItAsksForTheReset() {
        new LoginRecoveryRunner(configService, true).run(null);

        verify(configService).resetLocalLogin();
    }

    @Test
    void whenTheSwitchIsOffTheStoredRowIsLeftAlone() {
        new LoginRecoveryRunner(configService, false).run(null);

        verifyNoInteractions(configService);
    }
}
