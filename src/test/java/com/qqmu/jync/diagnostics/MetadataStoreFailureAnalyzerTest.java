package com.qqmu.jync.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guards the operator-facing explanation for the startup failure two overlapping Jync
 * processes (typically a one-click upgrade whose old JVM had not exited yet) produce:
 * Hibernate's generic "Unable to determine Dialect without JDBC metadata".
 */
class MetadataStoreFailureAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void theDialectFallbackBecomesAnActionableExplanation() {
        Path store = tempDir.resolve("data/jync");
        Environment env = new MockEnvironment()
                .withProperty("spring.datasource.url",
                        "jdbc:h2:file:" + store + ";MODE=MySQL;AUTO_SERVER=TRUE");

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env).analyze(wrappedFailure());

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
                .contains("元数据库")
                .contains(store.toString())
                .contains("Unable to determine Dialect without JDBC metadata")
                .contains("HHH100046")
                .contains("HHH000342");
        assertThat(analysis.getAction())
                .contains(store + ".lock.db")
                .contains("删除")
                .contains("logs/jync-restart.log")
                .contains("spring.datasource.url");
    }

    /**
     * Jync also documents MySQL / PostgreSQL / Oracle metadata stores. Those have no H2 lock
     * file, so the H2 advice ("delete .lock.db") would send the operator after a file that does
     * not exist, while the real cause (connection refused, bad credentials) goes unmentioned.
     */
    @Test
    void anExternalMetadataStoreGetsConnectionAdviceInsteadOfH2LockFileAdvice() {
        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:postgresql://db.internal:5432/jync?password=secret");

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env).analyze(wrappedFailure());

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
                .contains("db.internal")
                .contains("Unable to determine Dialect without JDBC metadata")
                .doesNotContain("secret");
        assertThat(analysis.getAction())
                .contains("db.internal")
                .contains("Connection refused")
                .doesNotContain(".lock.db")
                .doesNotContain("AUTO_SERVER")
                .doesNotContain("secret");
        assertThat(analysis.getAction()).doesNotContain(Path.of("./data/jync").toAbsolutePath()
                .normalize().toString());
    }

    @Test
    void theMatchLooksThroughTheWholeCauseChain() {
        Throwable deep = new RuntimeException("outer",
                new IllegalStateException("Spring wrapper",
                        new RuntimeException("Unable to determine Dialect without JDBC metadata "
                                + "(please set 'jakarta.persistence.jdbc.url' ...)")));

        assertThat(new MetadataStoreFailureAnalyzer(new MockEnvironment()).analyze(deep)).isNotNull();
    }

    @Test
    void anUnrelatedFailureIsLeftAlone() {
        Throwable failure = new IllegalStateException("Port 8080 is already in use",
                new java.net.BindException("Address already in use"));

        assertThat(new MetadataStoreFailureAnalyzer(new MockEnvironment()).analyze(failure)).isNull();
    }

    @Test
    void aFailureWithoutMessageDoesNotThrow() {
        assertThat(new MetadataStoreFailureAnalyzer(new MockEnvironment())
                .analyze(new IllegalStateException())).isNull();
    }

    @Test
    void aSelfReferencingCauseChainTerminates() {
        Throwable loop = new IllegalStateException("Unable to determine Dialect without JDBC metadata") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(new MetadataStoreFailureAnalyzer(new MockEnvironment()).analyze(loop)).isNotNull();
    }

    @Test
    void theAnalyzerIsRegisteredForSpringBootFailureAnalysis() {
        Environment env = new MockEnvironment();
        List<FailureAnalyzer> analyzers = SpringFactoriesLoader
                .forDefaultResourceLocation(getClass().getClassLoader())
                .load(FailureAnalyzer.class, SpringFactoriesLoader.ArgumentResolver.of(Environment.class, env),
                        SpringFactoriesLoader.FailureHandler.logging(
                                LogFactory.getLog(MetadataStoreFailureAnalyzerTest.class)));

        assertThat(analyzers).anyMatch(MetadataStoreFailureAnalyzer.class::isInstance);
    }

    private static Throwable wrappedFailure() {
        return new IllegalStateException(
                "Failed to initialize dependency 'EntityManagerFactory' of bean 'entityManagerFactory'",
                new IllegalStateException("Unable to determine Dialect without JDBC metadata "
                        + "(please set 'jakarta.persistence.jdbc.url', 'hibernate.connection.url', or "
                        + "'hibernate.dialect')"));
    }
}
