package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;

/**
 * The object picker posts one field pair per table row. Tomcat stops parsing at 10,000
 * parameters by default and silently drops the rest, which turns a save on a large schema
 * into "nothing selected". The connector customizer has to lift that limit.
 */
class FormParameterLimitConfigTest {

    @Test
    void raisesTheConnectorParameterLimitAboveTomcatsDefault() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setPort(0);
        new FormParameterLimitConfig().formParameterLimit().customize(factory);

        TomcatWebServer server = (TomcatWebServer) factory.getWebServer();
        try {
            // Spring Boot detaches the connectors during initialize() and re-attaches
            // them in start(); assert on the connector that actually serves requests.
            server.start();
            Connector connector = server.getTomcat().getConnector();
            assertThat(connector.getMaxParameterCount())
                    .isEqualTo(FormParameterLimitConfig.MAX_PARAMETER_COUNT);
        } finally {
            server.stop();
        }
    }
}
