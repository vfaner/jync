package com.qqmu.jync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import com.qqmu.jync.util.ClientIpResolver;

/**
 * Applies {@code app.security.trusted-proxies} to {@link ClientIpResolver} once at startup.
 *
 * <p>The resolver is a static utility because its callers — the security filter and the
 * audit trail — sit below the Spring bean graph. This configuration is the one place that
 * feeds it, so the trusted set is decided by configuration rather than by "the peer looked
 * private", which is what made the forwarding headers forgeable from the LAN.
 */
@Configuration
public class ClientIpConfig {

    public ClientIpConfig(
            @Value("${app.security.trusted-proxies:}") String trustedProxies) {
        ClientIpResolver.configureTrustedProxies(trustedProxies);
    }
}
