package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Iterator;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import com.qqmu.jync.config.SyncProperties;

/**
 * The {@code appVersion} model attribute: {@code v}-prefixed BuildProperties version when the
 * artifact carries build-info, and {@code null} (templates hide the markup) in IDE runs.
 */
class GlobalModelAdviceVersionTest {

    private GlobalModelAdvice adviceWith(BuildProperties props) {
        return new GlobalModelAdvice(
                "https://github.com/vfaner/jync", new SyncProperties(), providerOf(props), true);
    }

    /** Minimal ObjectProvider view: fixed instance or nothing, no bean factory involved. */
    private static ObjectProvider<BuildProperties> providerOf(BuildProperties props) {
        return new ObjectProvider<>() {
            @Override
            public BuildProperties getObject() {
                return props;
            }

            @Override
            public BuildProperties getObject(Object... args) {
                return props;
            }

            @Override
            public BuildProperties getIfAvailable() {
                return props;
            }

            @Override
            public BuildProperties getIfUnique() {
                return props;
            }

            @Override
            public Iterator<BuildProperties> iterator() {
                return Collections.emptyIterator();
            }
        };
    }

    @Test
    void prefixesBuildInfoVersion() {
        Properties p = new Properties();
        p.setProperty("version", "1.1.1");
        GlobalModelAdvice advice = adviceWith(new BuildProperties(p));

        assertThat(advice.appVersion()).isEqualTo("v1.1.1");
    }

    @Test
    void absentBuildPropertiesHidesVersion() {
        assertThat(adviceWith(null).appVersion()).isNull();
    }

    @Test
    void blankVersionHidesVersion() {
        assertThat(adviceWith(new BuildProperties(new Properties())).appVersion()).isNull();
    }

    @Test
    void formatsBuildTimeAsUtcStamp() {
        Properties p = new Properties();
        p.setProperty("time", "2026-10-15T13:32:06Z");
        assertThat(adviceWith(new BuildProperties(p)).buildTime())
                .isEqualTo("2026-10-15 13:32 UTC");
    }

    @Test
    void absentBuildPropertiesHidesBuildTime() {
        assertThat(adviceWith(null).buildTime()).isNull();
    }

    @Test
    void buildInfoWithoutTimeHidesBuildTime() {
        Properties p = new Properties();
        p.setProperty("version", "1.7.3");
        assertThat(adviceWith(new BuildProperties(p)).buildTime()).isNull();
    }
}
