package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The startup banner must not hard-code a version.
 *
 * <p>It did: v1.8.1 was tagged and published with a banner still reading {@code v1.8.0}, because
 * bumping the POM is what a release does and the banner is not part of it. The banner now reads
 * {@code ${app.version}}, and {@code app.version} is {@code @project.version@} in application.yml,
 * filtered by the build. The manifest route ({@code ${application.version}}) resolves only in the
 * packaged jar; the config route resolves under {@code spring-boot:run} too.
 */
class BannerVersionTest {

    /** A version as it appears in prose: v1.8.1, or 1.8.1 without the v. */
    private static final Pattern LITERAL_VERSION = Pattern.compile("\\bv?\\d+\\.\\d+(\\.\\d+)?");

    @Test
    @DisplayName("banner.txt takes its version from the build, not from a literal")
    void theBannerVersionFollowsThePom() throws Exception {
        String banner = read("banner.txt");

        assertThat(banner)
                .as("banner.txt must resolve the version from the configuration")
                .contains("${app.version}");
        // The regression this pins: a release bumps only the POM, and the banner keeps the
        // version it was born with.
        assertThat(banner)
                .as("banner.txt must not hard-code a version")
                .doesNotContainPattern(LITERAL_VERSION);
    }

    @Test
    @DisplayName("app.version is the POM version the build filtered in")
    void theVersionPropertyIsTheFilteredPomVersion() throws Exception {
        assertThat(bannerVersion()).as("app.version in application.yml — without @project.version@"
                        + " being filtered, the banner would render the literal or nothing")
                .matches("\\d+\\.\\d+(\\.\\d+)?([.-][0-9A-Za-z.-]+)?");
    }

    private static String bannerVersion() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertThat(sources).as("application.yml must load as YAML").isNotEmpty();
        return String.valueOf(sources.get(0).getProperty("app.version"));
    }

    private static String read(String classpathResource) throws Exception {
        return new String(new ClassPathResource(classpathResource).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
