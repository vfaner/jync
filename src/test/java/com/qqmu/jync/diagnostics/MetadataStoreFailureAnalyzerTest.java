package com.qqmu.jync.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
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

    /**
     * Hibernate throws the dialect fallback as a NEW exception and only logs the connection
     * error (HHH100046), so the cause chain carries nothing. The operator's only copy of the
     * real reason is the startup log the upgrade launcher writes; the analysis must quote it.
     */
    @Test
    void theUnderlyingErrorQuotedFromTheStartupLogBecomesPartOfTheDescription() throws Exception {
        Path store = tempDir.resolve("data/jync");
        Path logs = Files.createDirectories(tempDir.resolve("logs"));
        Files.writeString(logs.resolve("jync.log"),
                "2026-11-20T15:29:06.100+08:00  WARN 1 --- [jync] [main] "
                        + "org.hibernate.engine.jdbc.env.internal.JdbcEnvironmentInitiator : "
                        + "HHH100046: Could not obtain connection to query JDBC database metadata\n"
                        + "org.h2.jdbc.JdbcSQLNonTransientConnectionException: Database may be "
                        + "already in use: \"" + store + "\". Possible solutions [90020-224]\n"
                        + "\tat org.h2.message.DbException.getJdbcSQLException(DbException.java:690)\n");

        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:h2:file:" + store + ";MODE=MySQL;AUTO_SERVER=TRUE");

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env, tempDir, null)
                .analyze(wrappedFailure());

        assertThat(analysis.getDescription())
                .contains("最近一次启动日志")
                .contains("Database may be already in use")
                .contains("[90020-224]")
                .doesNotContain("at org.h2.message");
    }

    /** The same log quote helps an external store's "Connection refused" just as much. */
    @Test
    void aCredentialInTheQuotedLogLineIsRedacted() throws Exception {
        Path logs = Files.createDirectories(tempDir.resolve("logs"));
        Files.writeString(logs.resolve("jync.log"),
                "WARN 1 --- [jync] [main] x : "
                        + "HHH100046: Could not obtain connection to query JDBC database metadata\n"
                        + "java.sql.SQLException: Connection refused to db.internal:5432, "
                        + "password=hunter2\n");

        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:postgresql://db.internal:5432/jync?password=secret");

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env, tempDir, null)
                .analyze(wrappedFailure());

        assertThat(analysis.getDescription())
                .contains("最近一次启动日志")
                .contains("to db.internal:5432")
                .doesNotContain("hunter2")
                .doesNotContain("secret");
    }

    /** No log file (a manual console start) must not add an empty "see the log" sentence. */
    @Test
    void withoutAStartupLogTheDescriptionStaysAsBefore() {
        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:h2:file:" + tempDir.resolve("data/jync"));

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env, tempDir, null)
                .analyze(wrappedFailure());

        assertThat(analysis.getDescription()).doesNotContain("最近一次启动日志");
    }

    /**
     * The most common file-store causes are answerable without opening the store: a lock file,
     * an unwritable data directory, no space, an unwritable or missing store file.
     */
    @Test
    void theDescriptionStatesWhatTheFileStoreChecksFound() throws Exception {
        Path store = tempDir.resolve("data/jync");
        Files.createDirectories(store.getParent());
        Files.createFile(Path.of(store + ".mv.db"));
        Files.createFile(Path.of(store + ".lock.db"));
        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:h2:file:" + store + ";MODE=MySQL");

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env, tempDir, null)
                .analyze(wrappedFailure());

        assertThat(analysis.getDescription())
                .contains("启动自检")
                .contains("发现锁文件")
                .contains(store + ".lock.db")
                .contains("可写")
                .contains(store + ".mv.db")
                .doesNotContain("未发现锁文件");
    }

    @Test
    void anAbsentLockFileIsRuledOutInsteadOfHopedFor() throws Exception {
        Path store = tempDir.resolve("data/jync");
        Files.createDirectories(store.getParent());
        Files.createFile(Path.of(store + ".mv.db"));
        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:h2:file:" + store);

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env, tempDir, null)
                .analyze(wrappedFailure());

        assertThat(analysis.getDescription())
                .contains("未发现锁文件")
                .doesNotContain("发现锁文件 " + store);
    }

    /**
     * H2 writes the real open error to {@code <store>.trace.db} even when the application log
     * was never redirected to a file (a manual {@code java -jar} start, a console-only container).
     * That file is the operator's most reliable copy of the cause, so it is quoted too.
     */
    @Test
    void theLastH2TraceErrorIsQuotedWithItsTimestamp() throws Exception {
        Path store = tempDir.resolve("data/jync");
        Files.createDirectories(store.getParent());
        Files.createFile(Path.of(store + ".mv.db"));
        Files.writeString(Path.of(store + ".trace.db"),
                "2026-11-20 15:29:04 database: flush\n"
                        + "org.h2.message.DbException: General error: \"inner\" [50000-214]\n"
                        + "\tat org.h2.message.DbException.get(DbException.java:212)\n"
                        + "\n"
                        + "2026-11-20 15:29:05 database: opening " + store + "\n"
                        + "org.h2.message.DbException: Unsupported database file version or invalid "
                        + "file header in file \"" + store + ".mv.db\" [90048-214]\n"
                        + "\tat org.h2.message.DbException.get(DbException.java:212)\n");
        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:h2:file:" + store + ";MODE=MySQL;AUTO_SERVER=TRUE");

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env, tempDir, null)
                .analyze(wrappedFailure());

        assertThat(analysis.getDescription())
                .contains("最近一次 H2 诊断")
                .contains("2026-11-20 15:29:05")
                .contains("database: opening")
                .contains("Unsupported database file version")
                .doesNotContain("General error")
                .doesNotContain("at org.h2.message");
    }

    @Test
    void withoutATraceFileNoH2DiagnosticIsQuoted() {
        Path store = tempDir.resolve("data/jync");
        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:h2:file:" + store + ";MODE=MySQL");

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env, tempDir, null)
                .analyze(wrappedFailure());

        assertThat(analysis.getDescription()).doesNotContain("最近一次 H2 诊断");
    }

    /**
     * Jync pins H2 2.1.214 because every 1.x release wrote MVStore format 2 and H2 2.2+ refuses
     * that file. The inverse case is just as fatal and just as easy to name: a store written by
     * H2 2.2+ (format 3) cannot be opened by the bundled 2.1.214, and the operator needs to know
     * it is a version mismatch, not corruption.
     */
    @Test
    void aNewerMvStoreWriteFormatIsNamedAsAVersionMismatch() throws Exception {
        Path store = tempDir.resolve("data/jync");
        Files.createDirectories(store.getParent());
        Files.writeString(Path.of(store + ".mv.db"),
                "H:2,block:3,blockSize:1000,chunk:2,clean:1,created:1a1f80f45a3,format:3,"
                        + "version:2,fletcher:95b899e2\n");
        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:h2:file:" + store + ";MODE=MySQL");

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env, tempDir, null)
                .analyze(wrappedFailure());

        assertThat(analysis.getDescription())
                .contains("写格式 3")
                .contains("H2 2.2")
                .contains("2.1.214");
    }

    @Test
    void theSupportedWriteFormatIsReportedAsNormal() throws Exception {
        Path store = tempDir.resolve("data/jync");
        Files.createDirectories(store.getParent());
        Files.writeString(Path.of(store + ".mv.db"),
                "H:2,block:3,blockSize:1000,chunk:2,clean:1,created:1a1f80f8406,format:2,"
                        + "version:2,fletcher:a4cb6be4\n");
        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:h2:file:" + store + ";MODE=MySQL");

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env, tempDir, null)
                .analyze(wrappedFailure());

        assertThat(analysis.getDescription())
                .contains("写格式 2")
                .doesNotContain("H2 2.2");
    }

    @Test
    void aStoreFileThatIsNotAMvStoreIsCalledOutAsDamaged() throws Exception {
        Path store = tempDir.resolve("data/jync");
        Files.createDirectories(store.getParent());
        Files.writeString(Path.of(store + ".mv.db"), "not an h2 store");
        Environment env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:h2:file:" + store + ";MODE=MySQL");

        FailureAnalysis analysis = new MetadataStoreFailureAnalyzer(env, tempDir, null)
                .analyze(wrappedFailure());

        assertThat(analysis.getDescription()).contains("损坏");
    }

    private static Throwable wrappedFailure() {
        return new IllegalStateException(
                "Failed to initialize dependency 'EntityManagerFactory' of bean 'entityManagerFactory'",
                new IllegalStateException("Unable to determine Dialect without JDBC metadata "
                        + "(please set 'jakarta.persistence.jdbc.url', 'hibernate.connection.url', or "
                        + "'hibernate.dialect')"));
    }
}
