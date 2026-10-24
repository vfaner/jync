package com.qqmu.jync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.qqmu.jync.service.auth.oa.OaAuthConfigService;

import lombok.extern.slf4j.Slf4j;

/**
 * The rescue path for a lockout: an administrator turned local sign-in off and the external
 * provider then stopped working, so nobody can get back into the application.
 *
 * <p>Starting the application with {@code JYN_RESET_LOGIN=true} (one boot is enough) re-enables
 * the local form and saves the change; the administrator signs in locally, fixes the external
 * settings, and removes the variable. It is an emergency lever, not a second way to configure
 * sign-in: with the variable unset it does nothing at all.
 */
@Component
@Slf4j
public class LoginRecoveryRunner implements ApplicationRunner {

    private final OaAuthConfigService configService;
    private final boolean enabled;

    public LoginRecoveryRunner(OaAuthConfigService configService,
                               @Value("${app.login.reset-on-startup:false}") boolean enabled) {
        this.configService = configService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (configService.resetLocalLogin()) {
            log.warn("JYN_RESET_LOGIN is set: local sign-in was off and has been re-enabled and "
                    + "saved. Remove the variable and restart once the external authentication "
                    + "settings work again.");
        } else {
            log.info("JYN_RESET_LOGIN is set, but local sign-in is already allowed; nothing to do.");
        }
    }
}
